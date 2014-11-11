/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.search;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import sirius.kernel.commons.Strings;
import sirius.kernel.commons.Tuple;
import sirius.kernel.di.std.Part;
import sirius.kernel.health.Exceptions;
import sirius.kernel.health.HandledException;
import sirius.kernel.nls.NLS;
import sirius.search.annotations.RefField;
import sirius.search.annotations.RefType;
import sirius.search.annotations.Transient;
import sirius.search.annotations.Unique;
import sirius.search.properties.Property;
import sirius.web.http.WebContext;
import sirius.web.security.UserContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Base class for all types which are stored in ElasticSearch.
 * <p>
 * Each subclass should wear a {@link sirius.search.annotations.Indexed} annotation to indicate which index should be
 * used.
 * </p>
 *
 * @author Andreas Haufler (aha@scireum.de)
 * @since 2013/12
 */
public abstract class Entity {

    /**
     * Contains the unique ID of this entity. This is normally auto generated by ElasticSearch.
     */
    @Transient
    protected String id;
    public static final String ID = "id";

    /**
     * Contains the version number of the currently loaded entity. Used for optimistic locking e.g. in
     * {@link Index#tryUpdate(Entity)}
     */
    @Transient
    protected long version;

    /**
     * Determines if this entity is or will be deleted.
     */
    @Transient
    protected boolean deleted;

    /**
     * Original data loaded from the database (ElasticSearch)
     */
    @Transient
    protected Map<String, Object> source;

    /**
     * Creates and initializes a new instance.
     * <p>
     * All mapped properties will be initialized by their {@link Property} if necessary.
     * </p>
     */
    public Entity() {
        if (Index.schema != null) {
            for (Property p : Index.getDescriptor(getClass()).getProperties()) {
                try {
                    p.init(this);
                } catch (Throwable e) {
                    Index.LOG.WARN("Cannot initialize %s of %s", p.getName(), getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Determines if the entity is new.
     *
     * @return determines if the entity is new (<tt>true</tt>) or if it was loaded from the database (<tt>false</tt>).
     */
    public boolean isNew() {
        return id == null || Index.NEW.equals(id);
    }

    /**
     * Determines if the entity still exists and is not about to be deleted.
     *
     * @return <tt>true</tt> if the entity is neither new, nor marked as deleted. <tt>false</tt> otherwise
     */
    public boolean exists() {
        return !isNew() && !deleted;
    }

    /**
     * Determines if the entity is marked as deleted.
     *
     * @return <tt>true</tt> if the entity is marked as deleted, <tt>false</tt> otherwise
     */
    public boolean isDeleted() {
        return deleted;
    }

    /**
     * Returns the unique ID of the entity.
     * <p>
     * Unless the entity is new, this is never <tt>null</tt>.
     * </p>
     *
     * @return the id of this entity
     */
    public String getId() {
        return id;
    }

    /**
     * Returns an ID which is guaranteed to be globally unique.
     * <p>
     * Note that new entities always have default (non-unique) id.
     * </p>
     *
     * @return the globally unique ID of this entity.
     */
    public String getUniqueId() {
        return Index.getDescriptor(getClass()).getType() + "-" + id;
    }

    /**
     * Sets the ID of this entity.
     *
     * @param id the ID for this entity
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the version of this entity.
     *
     * @return the version which was loaded from the database
     */
    public long getVersion() {
        return version;
    }

    /**
     * Sets the version of this entity.
     *
     * @param version the version to set
     */
    public void setVersion(long version) {
        this.version = version;
    }

    /**
     * Sets the deleted flag.
     *
     * @param deleted the new value of the deleted flag
     */
    protected void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    /**
     * Invoked immediately before {@link #performSaveChecks()} and permits to fill missing values.
     */
    public void beforeSaveChecks() {
    }

    /**
     * Performs consistency checks before an entity is saved into the database.
     */
    public void performSaveChecks() {
        HandledException error = null;
        EntityDescriptor descriptor = Index.getDescriptor(getClass());
        for (Property p : descriptor.getProperties()) {

            if (p.getField().isAnnotationPresent(RefField.class)) {
                fillRefField(descriptor, p);
            }

            Object value = p.writeToSource(this);
            if (!p.isNullAllowed()) {
                error = checkNullability(error, p, value);
            }

            if (p.getField().isAnnotationPresent(Unique.class) && !Strings.isEmpty(value)) {
                error = checkUniqueness(error, descriptor, p, value);
            }

        }
        if (error != null) {
            throw error;
        }
    }

    protected HandledException checkUniqueness(HandledException previousError,
                                               EntityDescriptor descriptor,
                                               Property p,
                                               Object value) {
        Query<?> qry = Index.select(getClass()).eq(p.getName(), value);
        if (!isNew()) {
            qry.notEq(Index.ID_FIELD, id);
        }
        Unique unique = p.getField().getAnnotation(Unique.class);
        setupRoutingForUniquenessCheck(descriptor, qry, unique);
        if (qry.exists()) {
            UserContext.setFieldError(p.getName(), NLS.toUserString(value));
            if (previousError == null) {
                try {
                    return Exceptions.createHandled()
                                     .withNLSKey("Entity.fieldMustBeUnique")
                                     .set("field", p.getFieldTitle())
                                     .set("value", NLS.toUserString(p.getField().get(this)))
                                     .handle();
                } catch (Throwable e) {
                    Exceptions.handle(e);
                }
            }
        }
        return previousError;
    }

    private void setupRoutingForUniquenessCheck(EntityDescriptor descriptor, Query<?> qry, Unique unique) {
        if (Strings.isFilled(unique.within())) {
            qry.eq(unique.within(), descriptor.getProperty(unique.within()).writeToSource(this));
            try {
                if (descriptor.hasRouting()) {
                    Object routingKey = descriptor.getProperty(descriptor.getRouting()).writeToSource(this);
                    if (routingKey != null) {
                        qry.routing(routingKey.toString());
                    } else {
                        qry.deliberatelyUnrouted();
                        Exceptions.handle()
                                  .to(Index.LOG)
                                  .withSystemErrorMessage(
                                          "Performing a unique check on %s without any routing. This will be slow!",
                                          this.getClass().getName())
                                  .handle();
                    }
                }
            } catch (Exception e) {
                Exceptions.handle()
                          .to(Index.LOG)
                          .error(e)
                          .withSystemErrorMessage("Cannot determine routing key for '%s' of type %s",
                                                  this,
                                                  this.getClass().getName())
                          .handle();
                qry.deliberatelyUnrouted();
            }
        } else {
            qry.deliberatelyUnrouted();
        }
    }

    /**
     * Can be used to perform a null check for the given field and value.
     * <p>
     * This is internally used to check all properties which must not be null
     * ({@link sirius.search.properties.Property#isNullAllowed()}). If a field accepts a <tt>null</tt> value but
     * still must be field, this method can be called in {@link #beforeSaveChecks()}.
     * </p>
     *
     * @param previousError Can be used to signal that an error was already found. In this case the given exception
     *                      will be returned as result even if the value was <tt>null</tt>. In most cases this
     *                      parameter will be <tt>null</tt>.
     * @param property     the field to check
     * @param value         the value to check. If the value is <tt>null</tt> an error will be generated.
     * @return an error if either the given <tt>previousError</tt> was non null or if the given value was <tt>null</tt>
     */
    @Nullable
    protected HandledException checkNullability(@Nullable HandledException previousError,
                                                @Nonnull Property property,
                                                @Nullable Object value) {
        if (Strings.isEmpty(value)) {
            UserContext.setFieldError(property.getName(), null);
            if (previousError == null) {
                return Exceptions.createHandled()
                                 .withNLSKey("Entity.fieldMustBeFilled")
                                 .set("field", property.getFieldTitle())
                                 .handle();
            }
        }
        return previousError;
    }

    @SuppressWarnings("unchecked")
    protected void fillRefField(EntityDescriptor descriptor, Property p) {
        try {
            RefField ref = p.getField().getAnnotation(RefField.class);
            Property entityRef = descriptor.getProperty(ref.localRef());
            EntityDescriptor remoteDescriptor = Index.getDescriptor(entityRef.getField()
                                                                             .getAnnotation(RefType.class)
                                                                             .type());

            EntityRef<?> value = (EntityRef<?>) entityRef.getField().get(this);
            if (value.isValueLoaded() && !value.isDirty()) {
                // Update using value if present and not from cache
                if (value.isFilled()) {
                    p.getField()
                     .set(this, remoteDescriptor.getProperty(ref.remoteField()).getField().get(value.getValue()));
                } else {
                    p.getField().set(this, null);
                }
            } else if (value.isDirty()) {
                // Value hase changed - force load...
                Entity e = null;
                if (remoteDescriptor.getRouting() != null) {
                    // We need routing information to load the value....use the value provided in the
                    // RefType annotation...
                    Object routingValue = null;
                    String routingField = entityRef.getField().getAnnotation(RefType.class).localRouting();
                    if (Strings.isFilled(routingField)) {
                        routingValue = descriptor.getProperty(routingField).writeToSource(this);
                    }
                    if (routingValue == null) {
                        // No routing available or value was null -> fail
                        Exceptions.handle()
                                  .to(Index.LOG)
                                  .withSystemErrorMessage(
                                          "Error updating an RefField for an RefType: Property %s in class %s: No routing information was available to load the referenced value!",
                                          p.getName(),
                                          this.getClass().getName())
                                  .handle();
                    } else {
                        e = value.getValue((String) routingValue);
                    }
                } else {
                    // We need no routing -> simply load the value
                    e = value.getValue();
                }

                if (e == null) {
                    p.getField().set(this, null);
                } else {
                    p.getField().set(this, remoteDescriptor.getProperty(ref.remoteField()).getField().get(e));
                }
            }
        } catch (Throwable e) {
            Exceptions.handle()
                      .to(Index.LOG)
                      .error(e)
                      .withSystemErrorMessage(
                              "Error updating an RefField for an RefType: Property %s in class %s: %s (%s)",
                              p.getName(),
                              this.getClass().getName())
                      .handle();
        }
    }

    /**
     * Cascades the delete of an entity of this class.
     */
    protected void cascadeDelete() {
        for (ForeignKey fk : Index.getDescriptor(getClass()).remoteForeignKeys) {
            fk.onDelete(this);
        }
    }

    /**
     * Checks if an entity can be consistently deleted.
     */
    protected void performDeleteChecks() {
        for (ForeignKey fk : Index.getDescriptor(getClass()).remoteForeignKeys) {
            fk.checkDelete(this);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(id + " (Version: " + version + ") {");
        boolean first = true;
        for (Property p : Index.getDescriptor(getClass()).getProperties()) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append(p.getName());
            sb.append(": ");
            sb.append("'");
            sb.append(p.writeToSource(this));
            sb.append("'");
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (isNew()) {
            return false;
        }
        if (!(obj instanceof Entity)) {
            return false;
        }
        return getId().equals(((Entity) obj).getId());
    }

    @Override
    public int hashCode() {
        if (isNew()) {
            return super.hashCode();
        }
        return getId().hashCode();
    }

    /**
     * Invoked once an entity was completely saved.
     */
    public void afterSave() {
        for (ForeignKey fk : Index.getDescriptor(getClass()).remoteForeignKeys) {
            fk.onSave(this);
        }
    }

    /**
     * Loads the given list of values from a form submit in the given {@link WebContext}.
     *
     * @param ctx              the context which contains the data of the submitted form.
     * @param propertiesToRead the list of properties to read. This is used to have fine control over which values
     *                         are actually loaded from the form and which aren't.
     * @return a map of changed properties, containing the old and new value for each given property
     */
    public Map<String, Tuple<Object, Object>> load(WebContext ctx, String... propertiesToRead) {
        Map<String, Tuple<Object, Object>> changeList = Maps.newTreeMap();
        Set<String> allowedProperties = Sets.newTreeSet(Arrays.asList(propertiesToRead));
        for (Property p : Index.getDescriptor(getClass()).getProperties()) {
            if (allowedProperties.contains(p.getName())) {
                Object oldValue = p.writeToSource(this);
                p.readFromRequest(this, ctx);
                Object newValue = p.writeToSource(this);
                if (!Objects.equal(newValue, oldValue)) {
                    changeList.put(p.getName(), Tuple.create(oldValue, newValue));
                }
            }
        }

        return changeList;
    }

    /**
     * Invoked before an entity is saved into the database.
     * <p>
     * This method is not intended to be overridden. Override {@link #onSave()} or {@link #internalOnSave()}.
     * </p>
     */
    public final void beforeSave() {
        internalOnSave();
        onSave();
    }

    /**
     * Intended for classes providing additional on save handlers. Will be invoked before the entity will be saved,
     * but after it has been validated.
     * <p>
     * This method MUST call <code>super.internalOnSave</code> to ensure that all save handlers are called. This is
     * intended to be overridden by framework classes. Application classes should simply override
     * <code>onSave()</code>.
     * </p>
     */
    protected void internalOnSave() {

    }

    /**
     * Intended for classes providing on save handlers. Will be invoked before the entity will be saved,
     * but after it has been validated.
     * <p>
     * This method SHOULD call <code>super.internalOnSave</code> to ensure that all save handlers are called. However,
     * frameworks should rely on internalOnSave, which should not be overridden by application classes.
     * </p>
     */
    protected void onSave() {

    }

    /**
     * Enables tracking of source field (which contain the original state of the database before the entity was changed.
     * <p>
     * This will be set by @{@link Index#find(Class, String)}.
     * </p>
     */
    protected void initSourceTracing() {
        source = Maps.newTreeMap();
    }

    /**
     * Sets a source field when reading an entity from elasticsearch.
     * <p>
     * This is used by {@link Property#readFromSource(Entity, Object)}.
     * </p>
     *
     * @param name name of the field
     * @param val  persisted value of the field.
     */
    public void setSource(String name, Object val) {
        if (source != null) {
            source.put(name, val);
        }
    }

    /**
     * Checks if the given field has changed (since the entity was loaded from the database).
     *
     * @param field the field to check
     * @param value the current value which is to be compared
     * @return <tt>true</tt> if the value loaded from the database is not equal to the given value, <tt>false</tt>
     * otherwise.
     */
    protected boolean isChanged(String field, Object value) {
        return source != null && !Objects.equal(value, source.get(field));
    }

    /**
     * Returns the name of the index which is used to store the entities.
     *
     * @return the name of the ElasticSearch index used to store the entities. Returns <tt>null</tt> to indicate that
     * the default index (given by the {@link sirius.search.annotations.Indexed} annotation should be used).
     */
    public String getIndex() {
        return null;
    }

    /**
     * Generates an unique ID used to store new objects of this type. By default three types of IDs are supported:
     * <p>
     * <ul>
     * <li><b>ELASTICSEARCH</b>: Let elasticsearch generate the IDs.
     * Works 100% but contains characters like '-' or '_'</li>
     * <li><b>SEQUENCE</b>: Use a sequential generator to compute a new number.
     * Note that this implies a certain overhead to increment a cluster wide sequence.</li>
     * <li><b>BASE32HEX</b>: Use the internal generator (16 byte random data) represented as BASE32HEX
     * encoded string. This is the default setting.</li>
     * </ul>
     * </p>
     * <p>
     * Note that the type of generation can be controlled by overriding {@link #getIdGeneratorType()}.
     * </p>
     *
     * @return a unique ID used for new objects or <tt>null</tt> to let elasticsearch create one.
     */
    public String computePossibleId() {
        switch (getIdGeneratorType()) {
            case SEQUENCE:
                return String.valueOf(sequenceGenerator.getNextId(getClass().getSimpleName().toLowerCase()));
            case BASE32HEX:
                byte[] rndBytes = new byte[16];
                idGenerator.nextBytes(rndBytes);
                return BaseEncoding.base32Hex().encode(rndBytes).replace("=", "");
            default:
                return null;
        }
    }

    /**
     * Default types of id generators supported. {@link #computePossibleId()}.
     */
    public static enum IdGeneratorType {
        ELASTICSEARCH, SEQUENCE, BASE32HEX;
    }

    @Part
    private static IdGenerator sequenceGenerator;

    /*
     * Random generator used to compute IDs
     */
    private static SecureRandom idGenerator = new SecureRandom();

    /**
     * Used by the default implementation of {@link #computePossibleId()} to determine which kind of ID to generate.
     *
     * @return the preferred way of generating IDs.
     */
    protected IdGeneratorType getIdGeneratorType() {
        return IdGeneratorType.BASE32HEX;
    }
}
