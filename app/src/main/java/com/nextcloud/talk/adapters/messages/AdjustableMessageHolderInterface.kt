/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Christian Reiner <foss@christian-reiner.info>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.adapters.messages

import android.widget.RelativeLayout
import androidx.viewbinding.ViewBinding
import com.nextcloud.talk.databinding.ItemCustomOutcomingDeckCardMessageBinding
import com.nextcloud.talk.databinding.ItemCustomOutcomingLinkPreviewMessageBinding
import com.nextcloud.talk.databinding.ItemCustomOutcomingLocationMessageBinding
import com.nextcloud.talk.databinding.ItemCustomOutcomingPollMessageBinding
import com.nextcloud.talk.databinding.ItemCustomOutcomingTextMessageBinding
import com.nextcloud.talk.databinding.ItemCustomOutcomingVoiceMessageBinding
import com.nextcloud.talk.models.domain.ConversationModel
import com.nextcloud.talk.models.json.conversations.ConversationEnums.ConversationType

interface AdjustableMessageHolderInterface {

    val binding: ViewBinding

    fun adjustIfNoteToSelf(viewHolder: AdjustableMessageHolderInterface, currentConversation: ConversationModel?) {
        if (currentConversation?.type == ConversationType.NOTE_TO_SELF) {
            when (viewHolder.binding.javaClass) {
                ItemCustomOutcomingTextMessageBinding::class.java ->
                    (viewHolder.binding as ItemCustomOutcomingTextMessageBinding).bubble
                ItemCustomOutcomingDeckCardMessageBinding::class.java ->
                    (viewHolder.binding as ItemCustomOutcomingDeckCardMessageBinding).bubble
                ItemCustomOutcomingLinkPreviewMessageBinding::class.java ->
                    (viewHolder.binding as ItemCustomOutcomingLinkPreviewMessageBinding).bubble
                ItemCustomOutcomingPollMessageBinding::class.java ->
                    (viewHolder.binding as ItemCustomOutcomingPollMessageBinding).bubble
                ItemCustomOutcomingVoiceMessageBinding::class.java ->
                    (viewHolder.binding as ItemCustomOutcomingVoiceMessageBinding).bubble
                ItemCustomOutcomingLocationMessageBinding::class.java ->
                    (viewHolder.binding as ItemCustomOutcomingLocationMessageBinding).bubble
                else -> null
            }?.let {
                RelativeLayout.LayoutParams(binding.root.layoutParams).apply {
                    marginStart = 0
                    marginEnd = 0
                }.run {
                    it.layoutParams = this
                }
            }
        }
    }
}
