package com.example.areadtext

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ImageView
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
import com.example.areadtext.reader.EpubParser
import com.example.areadtext.ui.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 书架（启动页，legado 风格书架入口）：导入本地 EPUB → 解析缓存 → 打开阅读器。
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
            onOpen = { book -> openBook(book) },
            onLongPress = { book -> confirmDelete(book) },
        )
        binding.shelfList.layoutManager = GridLayoutManager(this, 3)
        binding.shelfList.adapter = adapter

        binding.fabImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/epub", "application/octet-stream", "*/*"))
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
                    EpubParser.cacheFile(this@ShelfActivity, book.bookId).delete()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 复制到私有目录 → 解析 → 写缓存 → 入库。 */
    private fun importBook(uri: Uri) {
        lifecycleScope.launch {
            binding.fabImport.isEnabled = false
            try {
                val imported = withContext(Dispatchers.IO) {
                    val displayName = queryDisplayName(uri) ?: "book_${System.currentTimeMillis()}.epub"
                    val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_")
                        .ifBlank { "book.epub" }
                    val booksDir = File(filesDir, "books").apply { if (!exists()) mkdirs() }
                    val target = File(booksDir, if (safeName.endsWith(".epub", true)) safeName else "$safeName.epub")
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext null

                    val bookId = "book_" + UUID.randomUUID().toString().substring(0, 8)
                    val book = EpubParser.parse(target.absolutePath, bookId)
                        ?: run { target.delete(); return@withContext null }
                    EpubParser.saveCache(this@ShelfActivity, book)
                    book
                }
                val book = imported ?: run {
                    android.widget.Toast.makeText(this@ShelfActivity, R.string.import_epub_failed, android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                dao.upsertBook(
                    BookEntity(
                        bookId = book.bookId,
                        title = book.title,
                        author = book.author,
                        filePath = book.filePath,
                        chapterCount = book.totalChapters,
                    )
                )
                android.widget.Toast.makeText(this@ShelfActivity, R.string.import_epub_done, android.widget.Toast.LENGTH_SHORT).show()
                openBook(dao.getBook(book.bookId)!!)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@ShelfActivity, R.string.import_epub_failed, android.widget.Toast.LENGTH_SHORT).show()
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
    private val onOpen: (BookEntity) -> Unit,
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
        holder.title.text = book.title
        holder.author.text = book.author.ifBlank { "" }
        holder.count.text = holder.itemView.context.getString(
            R.string.chapter_count, book.chapterCount
        )
        holder.cover.text = book.title.take(1)
        holder.itemView.setOnClickListener { onOpen(book) }
        holder.itemView.setOnLongClickListener { onLongPress(book); true }
    }

    class VH(binding: ItemShelfBookBinding) : RecyclerView.ViewHolder(binding.root) {
        val cover: TextView = binding.cover
        val title: TextView = binding.title
        val author: TextView = binding.author
        val count: TextView = binding.count
    }
}
