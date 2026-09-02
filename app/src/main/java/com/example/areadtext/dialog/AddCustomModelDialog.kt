package com.example.areadtext.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.areadtext.R
import com.example.areadtext.model.ModelCatalog
import com.example.areadtext.model.ModelManager

/**
 * 自定义模型添加对话框
 * 允许用户输入模型名称和下载链接来添加自定义语音识别模型
 */
class AddCustomModelDialog : DialogFragment() {

    private lateinit var nameInput: EditText
    private lateinit var urlInput: EditText
    private var listener: OnModelAddedListener? = null

    interface OnModelAddedListener {
        fun onModelAdded()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is OnModelAddedListener -> parentFragment as OnModelAddedListener
            context is OnModelAddedListener -> context
            else -> null
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val view = requireActivity().layoutInflater.inflate(
            R.layout.dialog_add_custom_model, null
        )

        nameInput = view.findViewById(R.id.model_name_input)
        urlInput = view.findViewById(R.id.model_url_input)

        builder.setView(view)
            .setTitle(R.string.custom_model_dialog_title)
            .setPositiveButton(R.string.custom_model_add, null)
            .setNegativeButton(R.string.custom_model_cancel, null)

        val dialog = builder.create()

        dialog.setOnShowListener {
            val addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

            addButton.setOnClickListener { addCustomModel() }
            cancelButton.setOnClickListener { dismiss() }
        }

        return dialog
    }

    private fun addCustomModel() {
        val name = nameInput.text.toString().trim()
        val url = urlInput.text.toString().trim()

        when {
            name.isEmpty() -> {
                Toast.makeText(
                    requireContext(),
                    R.string.custom_model_error_name_empty,
                    Toast.LENGTH_SHORT
                ).show()
            }
            !isValidUrl(url) -> {
                Toast.makeText(
                    requireContext(),
                    R.string.custom_model_error_url_invalid,
                    Toast.LENGTH_SHORT
                ).show()
            }
            else -> {
                val modelInfo = ModelCatalog.addCustom(requireContext(), url, name)
                ModelManager.download(requireContext(), modelInfo)
                Toast.makeText(
                    requireContext(),
                    R.string.custom_model_success,
                    Toast.LENGTH_SHORT
                ).show()
                listener?.onModelAdded()
                dismiss()
            }
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return android.util.Patterns.WEB_URL.matcher(url).matches()
    }
}