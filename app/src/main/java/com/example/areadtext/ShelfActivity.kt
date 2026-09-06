package com.example.areadtext

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.areadtext.data.AppDatabase
import com.example.areadtext.data.BookDao
import com.example.areadtext.data.BookEntity
import com.example.areadtext.databinding.ActivityShelfBinding
import com.example.areadtext.databinding.ItemShelfBookBinding
import com.example.areadtext.reader.book.Book
import com.example.areadtext.reader.book.BookCache
import com.example.areadtext.reader.book.ParserRegistry
import com.example.areadtext.reader.book.PdfBookParser
import com.example.areadtext.ui.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 书架（启动页，legado 风格书架入口）：导入本地图书（EPUB/PDF/TXT/MD）→ 解析缓存 → 打开阅读器。
 */
class ShelfActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShelfBinding
    private lateinit var dao: BookDao
    private lateinit var adapter: ShelfAdapter

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importBook(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShelfBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.shelf_title)

        dao = AppDatabase.get(this).bookDao()
        adapter = ShelfAdapter(
            open = { book -> openBook(book) },
            onLongPress = { book -> confirmDelete(book) },
        )
        binding.shelfList.layoutManager = GridLayoutManager(this, 3)
        binding.shelfList.adapter = adapter

        binding.fabImport.setOnClickListener {
            importLauncher.launch(
                arrayOf(
                    "application/epub+zip",
                    "application/pdf",
                    "text/plain",
                    "text/markdown",
                    "application/octet-stream",
                    "*/*"
                )
            )
        }

        lifecycleScope.launch {
            dao.observeBooks().collect { books ->
                adapter.submit(books)
                binding.emptyHint.isVisible = books.isEmpty()
            }
        }
    }

    private fun openBook(book: BookEntity) {
        lifecycleScope.launch {
            val progress = dao.getProgress(book.bookId)
            ReaderActivity.open(this@ShelfActivity, book.bookId, progress)
        }
    }

    private fun confirmDelete(book: BookEntity) {
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setMessage(R.string.delete_book_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    dao.deleteBook(book)
                    File(book.filePath).delete()
                    BookCache.file(this@ShelfActivity, book.bookId).delete()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 复制到私有目录 → 按扩展名选解析器 → 解析 → 写缓存 → 入库。 */
    private fun importBook(uri: Uri) {
        lifecycleScope.launch {
            binding.fabImport.isEnabled = false
            try {
                val imported = withContext(Dispatchers.IO) {
                    val displayName = queryDisplayName(uri) ?: "book_${System.currentTimeMillis()}"
                    val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_")
                        .ifBlank { "book" }
                    val ext = safeName.substringAfterLast('.', "").lowercase()

                    // 校验扩展名是否受支持
                    if (ext.isBlank() || !ParserRegistry.supportedExtensions().contains(ext)) {
                        return@withContext null
                    }

                    val booksDir = File(filesDir, "books").apply { if (!exists()) mkdirs() }
                    val target = File(booksDir, if (ext.isNotBlank()) "$safeName" else "$safeName.bin")
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext null

                    // 按扩展名选解析器
                    val parser = ParserRegistry.forFile(target)
                        ?: run { target.delete(); return@withContext null }

                    val bookId = "book_" + UUID.randomUUID().toString().substring(0, 8)
                    val book = parser.parse(target.absolutePath, bookId)
                        ?: run { target.delete(); return@withContext null }
                    parser.saveCache(this@ShelfActivity, book)
                    book to ext
                }

                val (book, ext) = imported ?: run {
                    // 针对 PDF 解析失败给出更精确的提示
                    val errorMsg = when (PdfBookParser.lastFailureReason) {
                        "scanned" -> getString(R.string.import_failed_scanned)
                        "low_quality" -> getString(R.string.import_failed_low_quality)
                        "no_pages" -> getString(R.string.import_failed_no_pages)
                        "empty" -> getString(R.string.import_failed_empty)
                        else -> getString(R.string.import_failed)
                    }
                    android.widget.Toast.makeText(this@ShelfActivity, errorMsg, android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }

                dao.upsertBook(
                    BookEntity(
                        bookId = book.bookId,
                        title = book.title,
                        author = book.author,
                        filePath = book.filePath,
                        chapterCount = book.totalChapters,
                        format = ext,
                    )
                )
                android.widget.Toast.makeText(this@ShelfActivity, R.string.import_done, android.widget.Toast.LENGTH_SHORT).show()
                openBook(dao.getBook(book.bookId)!!)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@ShelfActivity, R.string.import_failed, android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                binding.fabImport.isEnabled = true
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_shelf, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_models -> {
                startActivity(Intent(this, ModelManagerActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

class ShelfAdapter(
    private val open: (BookEntity) -> Unit,
    private val onLongPress: (BookEntity) -> Unit,
) : RecyclerView.Adapter<ShelfAdapter.VH>() {

    private var books: List<BookEntity> = emptyList()

    fun submit(list: List<BookEntity>) {
        books = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemShelfBookBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = books.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val book = books[position]
        val ctx = holder.itemView.context
        holder.title.text = book.title
        holder.author.text = book.author.ifBlank { "" }
        holder.count.text = ctx.getString(R.string.chapter_count, book.chapterCount)
        // 封面显示前 2 个字符
        holder.cover.text = book.title.take(2).ifBlank { "?" }
        // 格式角标
        holder.formatBadge.text = book.format.uppercase()
        // 封面颜色：根据 bookId hash 选色（12 色调色板）
        val palette = intArrayOf(
            0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF5350.toInt(), 0xFFFFCA28.toInt(),
            0xFF7E57C2.toInt(), 0xFF26C6DA.toInt(), 0xFFEC407A.toInt(), 0xFF9CCC65.toInt(),
            0xFFFF7043.toInt(), 0xFF42A5F5.toInt(), 0xFF8D6E63.toInt(), 0xFF78909C.toInt()
        )
        val colorIndex = Math.abs(book.bookId.hashCode()) % palette.size
        holder.cover.setBackgroundColor(palette[colorIndex])
        // 封面文字颜色：深色背景用白，浅色背景用深
        val textColor = if (colorIndex in intArrayOf(0, 4, 7, 10)) 0xFFFFFFFF.toInt() else 0xFF333333.toInt()
        holder.cover.setTextColor(textColor)

        // 阅读进度（简化版：显示已读%）
        val progress = daoProgress[book.bookId]
        holder.progress.text = if (progress != null && book.chapterCount > 0) {
            val pct = ((progress.coerceAtMost(book.chapterCount - 1) * 100) / book.chapterCount)
            "$pct%"
        } else { "" }

        holder.itemView.setOnClickListener { open(book) }
        holder.itemView.setOnLongClickListener { onLongPress(book); true }
    }

    /** 进度缓存（避免每个绑定都查询 DB）。 */
    private val daoProgress = HashMap<String, Int>()

    class VH(binding: ItemShelfBookBinding) : RecyclerView.ViewHolder(binding.root) {
        val cover: TextView = binding.cover
        val title: TextView = binding.title
        val author: TextView = binding.author
        val count: TextView = binding.count
        val formatBadge: TextView = binding.formatBadge
        val progress: TextView = binding.progress
    }
}
