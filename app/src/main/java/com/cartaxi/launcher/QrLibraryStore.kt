package com.cartaxi.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class QrLibraryEntry(
    val id: String, val title: String,
    val fileName: String, val addedAtMs: Long
)

/**
 * Offline multi-QR catalog. Selected image is mirrored as filesDir/qr.png
 * for the unchanged Android Auto renderer and its untouched quiet zone.
 *
 * Imported images are decoded and losslessly encoded to PNG on the phone;
 * no cropping, bilinear resize, OCR, scanning or network operations.
 */
class QrLibraryStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val directory = File(root, "qr_library")
    private val manifest = File(directory, "index.json")
    private val selectedMirror = File(root, "qr.png")
    private val imageLimitBytes = 10_000_000
    private val maxEntries = 30
    // A past process can die between writing index.json and the legacy
    // dashboard mirror. Reconcile once on first validated catalog read.
    private var mirrorChecked = false

    private data class Catalog(val selectedId: String?, val items: List<QrLibraryEntry>)

    @Synchronized fun entries(): List<QrLibraryEntry> = read().items
    @Synchronized fun current(): QrLibraryEntry? {
        val data = read()
        return data.items.firstOrNull { it.id == data.selectedId }
    }

    @Synchronized fun fileFor(id: String): File? =
        read().items.firstOrNull { it.id == id }?.let { File(directory, it.fileName) }

    @Synchronized fun add(title: String, png: ByteArray): QrLibraryEntry {
        val name = validatedTitle(title)
        validatePng(png)
        val old = read()
        check(old.items.size < maxEntries) { "Thư viện tối đa 30 mã QR." }
        val id = idFactory()
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,70}")) &&
            old.items.none { it.id == id }) { "Mã QR trùng hoặc không hợp lệ." }
        val item = QrLibraryEntry(id, name, "qr_$id.png", clock())
        val target = File(directory, item.fileName)
        // Write the image first. A failed catalog write leaves at most an
        // orphan file, never a metadata entry pointing at missing bytes.
        atomicWrite(target, png)
        try {
            if (old.selectedId == null) atomicWrite(selectedMirror, png)
            save(Catalog(old.selectedId ?: id, old.items + item))
        } catch (e: Exception) {
            target.delete()
            if (old.selectedId == null) selectedMirror.delete()
            mirrorChecked = false
            throw e
        }
        return item
    }

    @Synchronized fun select(id: String) {
        val old = read()
        val item = old.items.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Không tìm thấy mã QR.")
        if (old.selectedId == id) return
        val bytes = File(directory, item.fileName).readBytes()
        validatePng(bytes)
        try {
            atomicWrite(selectedMirror, bytes)
            save(old.copy(selectedId = id))
        } catch (e: Exception) {
            // Recover old selected QR if index write fails.
            mirrorChecked = false
            runCatching { read() }
            throw e
        }
    }

    @Synchronized fun rename(id: String, title: String) {
        val old = read()
        require(old.items.any { it.id == id }) { "Không tìm thấy mã QR." }
        save(old.copy(items = old.items.map {
            if (it.id == id) it.copy(title = validatedTitle(title)) else it
        }))
    }

    @Synchronized fun remove(id: String): Boolean {
        val old = read()
        val item = old.items.firstOrNull { it.id == id } ?: return false
        val remaining = old.items.filterNot { it.id == id }
        val selected = if (old.selectedId == id) remaining.firstOrNull()?.id
            else old.selectedId
        try {
            if (selected != old.selectedId) {
                if (selected == null) {
                    // Removing the last entry is explicit user action.
                    selectedMirror.delete()
                } else {
                    val img = remaining.first { it.id == selected }
                    val data = File(directory, img.fileName).readBytes()
                    validatePng(data)
                    atomicWrite(selectedMirror, data)
                }
            }
            save(Catalog(selected, remaining))
        } catch (e: Exception) {
            mirrorChecked = false
            runCatching { read() }
            throw e
        }
        File(directory, item.fileName).delete()
        return true
    }

    private fun validatedTitle(raw: String): String {
        val value = raw.trim()
        require(value.isNotBlank() && value.length <= 60) {
            "Tên mã QR phải từ 1 đến 60 ký tự."
        }
        return value
    }

    private fun validatePng(bytes: ByteArray) {
        require(bytes.size in 33..imageLimitBytes) { "Tệp QR PNG vượt giới hạn 10 MB." }
        val sig = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        require(sig.indices.all { bytes[it] == sig[it] } &&
            String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR") {
            "Cần ảnh PNG hợp lệ."
        }
        fun dimension(offset: Int) =
            ((bytes[offset].toInt() and 255) shl 24) or
            ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or
            (bytes[offset + 3].toInt() and 255)
        val w = dimension(16)
        val h = dimension(20)
        require(w in 1..2600 && h in 1..2600) {
            "Ảnh QR phải không quá 2600 điểm ảnh mỗi chiều."
        }
    }

    private fun read(): Catalog {
        if (!manifest.exists()) {
            if (!selectedMirror.exists()) return Catalog(null, emptyList())
            val bytes = selectedMirror.readBytes()
            validatePng(bytes)
            // One-time lossless migration; legacy file remains unchanged.
            val migrated = QrLibraryEntry("legacy", "Mã QR trước đây",
                "qr_legacy.png", clock())
            atomicWrite(File(directory, migrated.fileName), bytes)
            val catalog = Catalog(migrated.id, listOf(migrated))
            save(catalog)
            return catalog
        }
        require(manifest.length() in 1..50_000) { "Chỉ mục QR không hợp lệ." }
        val data = JSONObject(manifest.readText())
        require(data.getInt("version") == 1) { "Phiên bản QR chưa hỗ trợ." }
        val array = data.getJSONArray("items")
        require(array.length() <= maxEntries) { "Quá nhiều mã QR." }
        val items = (0 until array.length()).map { i ->
            val value = array.getJSONObject(i)
            QrLibraryEntry(
                value.getString("id"), validatedTitle(value.getString("title")),
                value.getString("fileName"), value.getLong("addedAtMs")
            )
        }
        require(items.map { it.id }.distinct().size == items.size &&
            items.all {
                it.id.matches(Regex("[a-zA-Z0-9_-]{1,70}")) &&
                    it.fileName == "qr_${it.id}.png" &&
                    File(directory, it.fileName).isFile
            }) { "Danh mục QR chứa đường dẫn hoặc dữ liệu không hợp lệ." }
        val selected = if (data.isNull("selectedId")) null else data.getString("selectedId")
        require(selected == null && items.isEmpty() ||
            selected != null && items.any { it.id == selected }) {
            "Mã QR đang chọn không có trong thư viện."
        }
        val catalog = Catalog(selected, items)
        if (!mirrorChecked) {
            // If a prior process died after mirroring but before committing
            // the index, restore the selected image from the authoritative
            // validated catalog, without modifying any saved QR originals.
            val expected = selected?.let {
                File(directory, items.first { item -> item.id == it }.fileName).readBytes()
            }
            if (expected == null) {
                if (selectedMirror.exists()) check(selectedMirror.delete()) {
                    "Không khôi phục được ảnh QR đã xóa."
                }
            } else if (!selectedMirror.isFile ||
                !selectedMirror.readBytes().contentEquals(expected)) {
                validatePng(expected)
                atomicWrite(selectedMirror, expected)
            }
            mirrorChecked = true
        }
        return catalog
    }

    private fun save(catalog: Catalog) {
        require(catalog.items.size <= maxEntries)
        val arr = JSONArray()
        catalog.items.forEach { item ->
            arr.put(JSONObject().put("id", item.id)
                .put("title", item.title)
                .put("fileName", item.fileName)
                .put("addedAtMs", item.addedAtMs))
        }
        val bytes = JSONObject().put("version", 1)
            .put("selectedId", catalog.selectedId ?: JSONObject.NULL)
            .put("items", arr).toString().toByteArray()
        atomicWrite(manifest, bytes)
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }
}
