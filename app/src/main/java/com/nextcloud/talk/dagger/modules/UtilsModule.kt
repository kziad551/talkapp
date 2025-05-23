/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Álvaro Brey <alvaro@alvarobrey.com>
 * SPDX-FileCopyrightText: 2022 Nextcloud GmbH
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.dagger.modules

import android.content.Context
import com.nextcloud.talk.utils.DateUtils
import com.nextcloud.talk.utils.message.MessageUtils
import com.nextcloud.talk.utils.permissions.PlatformPermissionUtil
import com.nextcloud.talk.utils.permissions.PlatformPermissionUtilImpl
import com.nextcloud.talk.utils.NotificationCoordinator
import dagger.Module
import dagger.Provides
import dagger.Reusable
import javax.inject.Singleton

@Module(includes = [ContextModule::class])
class UtilsModule {
    @Provides
    @Reusable
    fun providePermissionUtil(context: Context): PlatformPermissionUtil {
        return PlatformPermissionUtilImpl(context)
    }

    @Provides
    @Reusable
    fun provideDateUtils(context: Context): DateUtils {
        return DateUtils(context)
    }

    @Provides
    @Reusable
    fun provideMessageUtils(context: Context): MessageUtils {
        return MessageUtils(context)
    }
    
    @Provides
    @Singleton
    fun provideNotificationCoordinator(context: Context): NotificationCoordinator {
        return NotificationCoordinator(context)
    }
}
