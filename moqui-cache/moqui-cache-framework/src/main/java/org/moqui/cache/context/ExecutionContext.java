/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.cache.context;

import org.moqui.util.ContextBinding;
import org.moqui.util.ContextStack;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Interface definition for object used throughout the Moqui Framework to manage contextual execution information and
 * tool interfaces. One instance of this object will exist for each thread running code and will be applicable for that
 * thread only.
 */
@SuppressWarnings("unused")
public interface ExecutionContext {
    /** Get the ExecutionContextFactory this came from. */
    @Nonnull ExecutionContextFactory getFactory();

    /** Returns a Map that represents the current local variable space (context) in whatever is being run. */
    @Nonnull ContextStack getContext();
    @Nonnull ContextBinding getContextBinding();
    /** Returns a Map that represents the global/root variable space (context), ie the bottom of the context stack. */
    @Nonnull Map<String, Object> getContextRoot();

    /** Get an instance object from the named ToolFactory instance (loaded by configuration). Some tools return a
     * singleton instance, others a new instance each time it is used and that instance is saved with this
     * ExecutionContext to be reused. The instanceClass may be null in scripts or other contexts where static typing
     * is not needed */
    <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters);

    /** For managing and accessing caches. */
    @Nonnull CacheFacade getCache();

    /** This should be called when the ExecutionContext won't be used any more. Implementations should make sure
     * any active transactions, database connections, etc are closed.
     */
    void destroy();
}
