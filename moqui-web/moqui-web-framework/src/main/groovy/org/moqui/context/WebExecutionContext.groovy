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
package org.moqui.context;

import groovy.lang.Closure;
import org.moqui.entity.EntityFacade;
import org.moqui.screen.ScreenFacade;
import org.moqui.service.ServiceFacade;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * Interface definition for object used throughout the Moqui Framework to manage contextual execution information and
 * tool interfaces. One instance of this object will exist for each thread running code and will be applicable for that
 * thread only.
 */
@SuppressWarnings("unused")
public interface WebExecutionContext extends ExecutionContext {

    /** If running through a web (HTTP servlet) request offers access to the various web objects/information.
     * If not running in a web context will return null.
     */
    @Nullable
    <T> T getWeb();

    /** For accessing resources by location string (http://, jar://, component://, content://, classpath://, etc). */
    @Nonnull
    <T> T getResource();

    /** For trace, error, etc logging to the console, files, etc. */
    @Nonnull
    <T> T getLogger();

    /** For managing and accessing caches. */
    @Nonnull
    CacheFacade getCache();

    /** For transaction operations use this facade instead of the JTA UserTransaction and TransactionManager. See javadoc comments there for examples of code usage. */
    @Nonnull
    TransactionFacade getTransaction();

    /** For interactions with a relational database. */
    @Nonnull
    <T> T getEntity();

    /** For calling services (local or remote, sync or async or scheduled). */
    @Nonnull
    <T> T getService();

    /** For rendering screens for general use (mostly for things other than web pages or web page snippets). */
    @Nonnull
    ScreenFacade getScreen();

    @Nonnull
    NotificationMessage makeNotificationMessage();
    @Nonnull List<NotificationMessage> getNotificationMessages(@Nullable String topic);

    /** This should be called by a filter or servlet at the beginning of an HTTP request to initialize a web facade
     * for the current thread. */
    void initWebFacade(@Nonnull String webappMoquiName, @Nonnull HttpServletRequest request, @Nonnull HttpServletResponse response);

    void setWebFacade(WebFacade wf);
}
