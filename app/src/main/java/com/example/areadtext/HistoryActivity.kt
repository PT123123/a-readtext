package com.example.areadtext

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.areadtext.data.AppDatabase
import com.example.areadtext.data.TranscriptRepository
import com.example.areadtext.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val repo by lazy { TranscriptRepository(AppDatabase.get(this).transcriptDao()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        HistoryListController(this, binding.recycler, binding.empty, repo).start()
    }
}
