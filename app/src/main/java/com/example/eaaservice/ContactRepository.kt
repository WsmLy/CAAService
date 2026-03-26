package com.example.eaaservice.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.eaaservice.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SerializableContact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val colorValue: Long
)

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "contacts")

class ContactRepository(private val context: Context) {
    
    companion object {
        private val CONTACTS_KEY = stringPreferencesKey("contacts_list")
    }
    
    val contactsFlow: Flow<List<Contact>> = context.dataStore.data.map { preferences ->
        val json = preferences[CONTACTS_KEY] ?: ""
        if (json.isEmpty()) {
            emptyList()
        } else {
            try {
                val serializableContacts = Json.decodeFromString<List<SerializableContact>>(json)
                serializableContacts.map { it.toContact() }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    
    suspend fun saveContacts(contacts: List<Contact>) {
        context.dataStore.edit { preferences ->
            val serializableContacts = contacts.map { it.toSerializable() }
            val json = Json.encodeToString(serializableContacts)
            preferences[CONTACTS_KEY] = json
        }
    }
    
    suspend fun addContact(contact: Contact) {
        val currentContacts = contactsFlow.first()
        saveContacts(currentContacts + contact)
    }
    
    suspend fun updateContact(contact: Contact) {
        val currentContacts = contactsFlow.first()
        saveContacts(currentContacts.map { 
            if (it.id == contact.id) contact else it 
        })
    }
    
    suspend fun deleteContact(contactId: String) {
        val currentContacts = contactsFlow.first()
        saveContacts(currentContacts.filter { it.id != contactId })
    }
}

fun Contact.toSerializable(): SerializableContact {
    return SerializableContact(
        id = id,
        name = name,
        phoneNumber = phoneNumber,
        colorValue = color.value.toLong()
    )
}

fun SerializableContact.toContact(): Contact {
    return Contact(
        id = id,
        name = name,
        phoneNumber = phoneNumber,
        color = androidx.compose.ui.graphics.Color(colorValue.toULong())
    )
}
