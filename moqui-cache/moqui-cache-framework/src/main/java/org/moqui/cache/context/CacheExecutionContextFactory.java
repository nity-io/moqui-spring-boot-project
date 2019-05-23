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

import org.moqui.context.CacheFacade;
import org.moqui.context.ExecutionContextFactory;
import org.moqui.context.ToolFactory;

import javax.annotation.Nonnull;

/**
 * Interface for the object that will be used to get an ExecutionContext object and manage framework life cycle.
 */
public interface CacheExecutionContextFactory extends ExecutionContextFactory {

    /** For managing and accessing caches. */
    @Nonnull CacheFacade getCache();

}
