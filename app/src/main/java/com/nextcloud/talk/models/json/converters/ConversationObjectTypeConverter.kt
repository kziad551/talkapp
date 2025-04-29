/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2023 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.models.json.converters

import com.bluelinelabs.logansquare.typeconverters.StringBasedTypeConverter
import com.nextcloud.talk.models.json.conversations.ConversationEnums

class ConversationObjectTypeConverter : StringBasedTypeConverter<ConversationEnums.ObjectType>() {
    override fun getFromString(string: String?): ConversationEnums.ObjectType {
        return when (string) {
            "share:password" -> ConversationEnums.ObjectType.SHARE_PASSWORD
            "room" -> ConversationEnums.ObjectType.ROOM
            "file" -> ConversationEnums.ObjectType.FILE
            else -> ConversationEnums.ObjectType.DEFAULT
        }
    }

    override fun convertToString(`object`: ConversationEnums.ObjectType?): String {
        if (`object` == null) {
            return ""
        }

        return when (`object`) {
            ConversationEnums.ObjectType.SHARE_PASSWORD -> "share:password"
            ConversationEnums.ObjectType.ROOM -> "room"
            ConversationEnums.ObjectType.FILE -> "file"
            else -> ""
        }
    }
}
