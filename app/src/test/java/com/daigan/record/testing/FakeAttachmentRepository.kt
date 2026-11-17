package com.daigan.record.testing

import android.net.Uri
import com.daigan.record.data.model.Attachment
import com.daigan.record.data.repository.AttachmentRepository
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** JVM stand-in: attachment bytes are Android-only, so every mutation is unavailable. */
class FakeAttachmentRepository : AttachmentRepository {

    override fun observeForOrder(orderId: String): Flow<List<Attachment>> = flowOf(emptyList())

    override suspend fun addAttachment(
        taskDefinitionId: String?,
        occurrenceId: String?,
        sourceUri: Uri,
    ): Attachment = error("FakeAttachmentRepository cannot add attachments")

    override suspend fun replaceAttachment(attachmentId: String, sourceUri: Uri): Attachment =
        error("FakeAttachmentRepository cannot replace attachments")

    override suspend fun deleteAttachment(attachmentId: String) =
        error("FakeAttachmentRepository cannot delete attachments")

    override fun fileFor(attachment: Attachment): File = error("no files in the fake")

    override suspend fun cleanupOrphans(): Int = 0
}
