/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.dagger.modules

import com.nextcloud.talk.services.MessageNotificationDetectionService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class ServicesModule {
    
    @Provides
    @Singleton
    fun providesMessageNotificationDetectionService(): MessageNotificationDetectionService {
        return MessageNotificationDetectionService()
    }
} 