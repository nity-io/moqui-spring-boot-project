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
package org.moqui.context

import org.apache.shiro.authc.credential.CredentialsMatcher;
import org.moqui.impl.context.NotificationMessageImpl;
import org.moqui.impl.context.WebExecutionContextFactoryImpl
import org.moqui.impl.webapp.NotificationWebSocketListener;
import org.moqui.screen.ScreenFacade
import org.moqui.util.MNode;

import javax.annotation.Nonnull;

/**
 * Interface for the object that will be used to get an ExecutionContext object and manage framework life cycle.
 */
public interface WebExecutionContextFactory extends ServiceExecutionContextFactory{

    /** For rendering screens for general use (mostly for things other than web pages or web page snippets). */
    @Nonnull ScreenFacade getScreen();

    void registerNotificationMessageListener(@Nonnull NotificationMessageListener nml);

    void sendNotificationMessageToTopic(NotificationMessageImpl nmi);

    WebExecutionContextFactoryImpl.WebappInfo getWebappInfo(String webappName);

    NotificationWebSocketListener getNotificationWebSocketListener();

    CredentialsMatcher getCredentialsMatcher(String hashType, boolean isBase64);

    MNode getWebappNode(String webappName);

}
