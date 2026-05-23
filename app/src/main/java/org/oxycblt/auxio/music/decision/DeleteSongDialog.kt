/*
 * Copyright (c) 2024 Auxio Project
 * DeleteSongDialog.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.music.decision

import android.app.Activity
import android.app.RecoverableSecurityException
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogDeleteSongBinding
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.unlikelyToBeNull
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewBindingMaterialDialogFragment] that asks the user to confirm deletion of a [Song] file
 * from the device by typing "delete".
 */
@AndroidEntryPoint
class DeleteSongDialog : ViewBindingMaterialDialogFragment<DialogDeleteSongBinding>() {
    private val pickerModel: PlaylistPickerViewModel by viewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val args: DeleteSongDialogArgs by navArgs()

    private val deleteMediaLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                musicModel.refresh()
                requireContext().showToast(R.string.lng_song_deleted)
            }
            findNavController().navigateUp()
        }

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setTitle(R.string.lbl_confirm_delete_song)
            .setPositiveButton(R.string.lbl_apply, null)
            .setNegativeButton(R.string.lbl_cancel, null)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogDeleteSongBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogDeleteSongBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        musicModel.songDecision.consume()
        pickerModel.setSongToDelete(args.songUid)
        collectImmediately(pickerModel.currentSongToDelete, ::updateSongToDelete)
    }

    override fun onStart() {
        super.onStart()
        val dialog = requireDialog() as AlertDialog
        val applyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

        // Confirm button starts disabled until the user types the required word
        applyButton.isEnabled = false
        applyButton.setOnClickListener {
            performDelete(unlikelyToBeNull(pickerModel.currentSongToDelete.value))
        }

        requireBinding().deletionConfirmInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val confirmWord = getString(R.string.lbl_delete_confirm_word)
                    applyButton.isEnabled = s?.toString()?.trim()?.equals(confirmWord, ignoreCase = true) == true
                }
            }
        )
    }

    private fun updateSongToDelete(song: Song?) {
        if (song == null) {
            L.d("No song to delete, navigating away")
            findNavController().navigateUp()
            return
        }
        val confirmWord = getString(R.string.lbl_delete_confirm_word)
        requireBinding().deletionInfo.text =
            getString(R.string.fmt_deletion_info, song.name.resolve(requireContext()))
        requireBinding().deletionConfirmHint.text =
            getString(R.string.fmt_type_to_confirm, confirmWord)
    }

    private fun performDelete(song: Song) {
        val contentResolver = requireContext().contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(song.uri))
            deleteMediaLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        contentResolver.delete(song.uri, null, null)
                    } catch (e: SecurityException) {
                        val recoverable = e as? RecoverableSecurityException
                        if (recoverable != null) {
                            withContext(Dispatchers.Main) {
                                deleteMediaLauncher.launch(
                                    IntentSenderRequest.Builder(
                                        recoverable.userAction.actionIntent.intentSender
                                    ).build()
                                )
                            }
                            return@launch
                        }
                        throw e
                    }
                } else {
                    contentResolver.delete(song.uri, null, null)
                }
                withContext(Dispatchers.Main) {
                    musicModel.refresh()
                    requireContext().showToast(R.string.lng_song_deleted)
                    findNavController().navigateUp()
                }
            } catch (e: Exception) {
                L.e("Failed to delete song: $e")
                withContext(Dispatchers.Main) {
                    requireContext().showToast(R.string.err_delete_song_failed)
                    findNavController().navigateUp()
                }
            }
        }
    }
}
