package com.passlock.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Dependency-free binary (de)serialization of a [Vault].
 * Pure JVM so it is unit-testable and shared by the Android app.
 */
object VaultSerialization {
    private const val VERSION = 3

    fun encode(vault: Vault): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(VERSION)
            out.writeInt(vault.items.size)
            for (item in vault.items) {
                out.writeUTF(item.id)
                out.writeUTF(item.title)
                out.writeUTF(item.template.name)
                out.writeUTF(item.primaryFieldId ?: "")
                out.writeUTF(item.icon ?: "")
                out.writeBoolean(item.favorite)
                out.writeLong(item.createdAt)
                out.writeLong(item.updatedAt)
                out.writeInt(item.tags.size)
                for (tag in item.tags) out.writeUTF(tag)
                out.writeInt(item.fields.size)
                for (field in item.fields) {
                    out.writeUTF(field.id)
                    out.writeUTF(field.label)
                    out.writeUTF(field.value)
                    out.writeUTF(field.type.name)
                    out.writeBoolean(field.isSecret)
                    out.writeBoolean(field.requireBiometric)
                }
                out.writeInt(item.attachments.size)
                for (att in item.attachments) out.writeUTF(att)
            }
        }
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): Vault {
        DataInputStream(ByteArrayInputStream(bytes)).use { inp ->
            val version = inp.readInt()
            require(version in 1..VERSION) { "unsupported vault version $version" }
            val itemCount = inp.readInt()
            val items = ArrayList<Item>(itemCount)
            repeat(itemCount) {
                val id = inp.readUTF()
                val title = inp.readUTF()
                val template = Template.valueOf(inp.readUTF())
                val primary = inp.readUTF().ifEmpty { null }
                val icon = inp.readUTF().ifEmpty { null }
                val favorite = inp.readBoolean()
                val createdAt = inp.readLong()
                val updatedAt = inp.readLong()
                val tagCount = inp.readInt()
                val tags = ArrayList<String>(tagCount)
                repeat(tagCount) { tags.add(inp.readUTF()) }
                val fieldCount = inp.readInt()
                val fields = ArrayList<Field>(fieldCount)
                repeat(fieldCount) {
                    val fid = inp.readUTF()
                    val flabel = inp.readUTF()
                    val fvalue = inp.readUTF()
                    val ftype = FieldType.valueOf(inp.readUTF())
                    val fsecret = inp.readBoolean()
                    val freqBio = if (version >= 3) inp.readBoolean() else false
                    fields.add(Field(fid, flabel, fvalue, ftype, fsecret, freqBio))
                }
                val attachments = if (version >= 2) {
                    val attCount = inp.readInt()
                    ArrayList<String>(attCount).apply { repeat(attCount) { add(inp.readUTF()) } }
                } else {
                    emptyList()
                }
                items.add(Item(id, title, template, fields, tags, favorite, primary, icon, createdAt, updatedAt, attachments))
            }
            return Vault(items)
        }
    }
}
