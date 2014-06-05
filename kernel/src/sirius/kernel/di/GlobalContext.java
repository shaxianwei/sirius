/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.kernel.di;

import sirius.kernel.commons.Tuple;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Used to access parts managed by the {@link Injector}.
 * <p>
 * This is the central repository containing all parts managed by the injector. Parts stored inhere can be either
 * accessed via the <tt>getPart</tt> or <tt>findPart</tt> methods. Also all annotations processed by an appropriate
 * {@link FieldAnnotationProcessor} (like {@link sirius.kernel.di.std.Part}) will use this context to find the
 * requested part.
 * </p>
 *
 * @author Andreas Haufler (aha@scireum.de)
 * @since 2013/08
 */
public interface GlobalContext {

    /**
     * Finds the previously registered part for the given lookup class.
     * <p>
     * If several parts where registered for this class, the first one is chosen. If no part was registered,
     * <tt>null</tt> is returned.
     *
     * @param clazz the class used to lookup the requested part.
     * @return the first part registered for the given class or <tt>null</tt> if no part was registered yet.
     */
    @Nullable
    <P> P getPart(@Nonnull Class<P> clazz);

    /**
     * Retrieves a part of the requested type with the given unique name.
     * <p>
     * If no matching part is found, <tt>null</tt> is returned.
     * </p>
     *
     * @param uniqueName the name for which the part was registered
     * @param clazz      one of the lookup classes for which the part was registered
     * @return the part which the given unique name, registered for the given class, or <tt>null</tt> if no matching
     * part was found.
     */
    @Nullable
    <P> P getPart(@Nonnull String uniqueName, @Nonnull Class<P> clazz);

    /**
     * Like {@link #getPart(String, Class)} this method tried to find the part with the given name, registered for the
     * given lookup class. Rather than returning <tt>null</tt> when no part is found, this throws a
     * {@link sirius.kernel.health.HandledException} with an appropriate message.
     *
     * @param uniqueName the name for which the part was registered
     * @param clazz      one of the lookup classes for which the part was registered
     * @return the part which the given unique name, registered for the given class.
     * @throws sirius.kernel.health.HandledException if no matching part was found
     */
    @Nonnull
    <P> P findPart(@Nonnull String uniqueName, @Nonnull Class<P> clazz);

    /**
     * Returns all parts which are currently registered for the given lookup class.
     *
     * @param partInterface one of the lookup classes for which the parts of interest were registered
     * @return a collection of all parts registered for the given class. If no parts were found,
     * an empty collection is returned
     */
    @Nonnull
    <P> Collection<P> getParts(@Nonnull Class<? extends P> partInterface);

    /**
     * Returns all parts which are currently registered for the given lookup class and have a name attached.
     *
     * @param partInterface one of the lookup classes for which the parts of interest were registered
     * @return a collection of all parts registered for the given class with a name. If no parts were found,
     * an empty collection is returned.
     */
    @Nonnull
    <P> Collection<Tuple<String, P>> getNamedParts(@Nonnull Class<P> partInterface);

    /**
     * Returns a {@link PartCollection} which contains all parts registered for the given lookup class.
     *
     * @param partInterface one of the lookup classes for which the parts of interest were registered
     * @return a <tt>PartCollection</tt> containing all parts registered for the given class. If no parts were found,
     * an empty collection is returned
     */
    @Nonnull
    <P> PartCollection<P> getPartCollection(@Nonnull Class<P> partInterface);

    /**
     * Processes all annotations of the given objects class (or super classes).
     *
     * @param object the object which annotations should be processed to fill the respective fields
     * @return the "wired" object, which has no filled fields. This is just returned for convenience and will not
     * another instance or clone of the given object.
     */
    @Nonnull
    <T> T wire(@Nonnull T object);

    /**
     * Tries to find a <b>factory</b> with the given name to create an instance of the given type.
     * <p>
     * A factory is a method which wears a {@link sirius.kernel.di.std.Register} annotation. This mechanism can be
     * used in cases where a part is an overkill.
     * </p>
     *
     * @param type        the type of object produced by the factory
     * @param factoryName the name of the factory used
     * @return an object produced by the factory, wrapped as {@link java.util.Optional}. If no appropriate factory is
     * found, an empty Optional will be returned.
     */
    @Nonnull
    <P> Optional<P> make(Class<P> type, String factoryName);

    /**
     * Tries to find a <b>factory</b> with the given name.
     * <p>
     * A factory is a method which wears a {@link sirius.kernel.di.std.Register} annotation. This mechanism can be
     * used in cases where a part is an overkill.
     * </p>
     *
     * @param type        the type of object produced by the factory
     * @param factoryName the name of the factory used
     * @return a factory which produces objects of the given type. If no factory was found, a default implementation
     * will be returned which produces empty optionals.
     */
    @Nonnull
    <P> Supplier<Optional<P>> getFactory(Class<P> type, String factoryName);

}