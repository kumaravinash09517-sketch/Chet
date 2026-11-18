package com.example.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.data.local.UserEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceContact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val matchedDirectoryUser: UserEntity? = null
)

/**
 * On-demand in-app contact picker helper.
 * Reads device contacts ONLY after the user explicitly grants READ_CONTACTS
 * and opens the in-app contact picker sheet.
 */
class ContactSyncHelper(private val context: Context) {

    fun hasContactPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun loadPickerContacts(directoryUsers: List<UserEntity>): List<DeviceContact> =
        withContext(Dispatchers.IO) {
            if (!hasContactPermission()) return@withContext emptyList()

            val contactsMap = linkedMapOf<String, DeviceContact>()
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getString(idIdx) ?: "" else ""
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim() ?: "" else ""
                    val phone = if (numIdx >= 0) cursor.getString(numIdx)?.trim() ?: "" else ""
                    if (name.isNotBlank() && phone.isNotBlank() && !contactsMap.containsKey(id)) {
                        val normalizedPhone = phone.filter { it.isDigit() }
                        val match = directoryUsers.firstOrNull { dirUser ->
                            dirUser.displayName.equals(name, ignoreCase = true) ||
                                (normalizedPhone.length >= 6 &&
                                    dirUser.userId.filter { it.isDigit() }.endsWith(normalizedPhone.takeLast(6)))
                        }
                        contactsMap[id] = DeviceContact(
                            id = id,
                            name = name,
                            phoneNumber = phone,
                            matchedDirectoryUser = match
                        )
                    }
                }
            }
            contactsMap.values.toList()
        }
}
