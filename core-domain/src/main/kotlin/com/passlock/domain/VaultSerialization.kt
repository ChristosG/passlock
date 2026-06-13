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
    private const val VERSION = 1

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
                }
            }
        }
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): Vault {
        DataInputStream(ByteArrayInputStream(bytes)).use { inp ->
            val version = inp.readInt()
            require(version == VERSION) { "unsupported vault version $version" }
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
                    fields.add(
                        Field(
                            id = inp.readUTF(),
                            label = inp.readUTF(),
                            value = inp.readUTF(),
                            type = FieldType.valueOf(inp.readUTF()),
                            isSecret = inp.readBoolean(),
                        ),
                    )
                }
                items.add(Item(id, title, template, fields, tags, favorite, primary, icon, createdAt, updatedAt))
            }
            return Vault(items)
        }
    }
}
