package dev.nekuz.localmind

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView

class RowButton(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

sealed class Row {
    data class Header(val text: String) : Row()

    data class Note(val text: String) : Row()

    data class Value(
        val label: String,
        val value: String,
        val tag: String? = null,
        val tagColor: Int = R.color.text_secondary,
        val highlighted: Boolean = false,
    ) : Row()

    data class Message(val label: String, val text: String, val mine: Boolean, val copyText: String) : Row()

    data class Code(val language: String, val code: String) : Row()

    class Action(
        val title: String,
        val detail: String,
        val tag: String? = null,
        val tagColor: Int = R.color.text_secondary,
        val progress: Int? = null,
        val indeterminate: Boolean = false,
        val buttons: List<RowButton> = emptyList(),
        val highlighted: Boolean = false,
    ) : Row()

    class Picture(val bitmap: Bitmap, val button: RowButton) : Row()
}

class RowAdapter(private val context: Context) : BaseAdapter() {
    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val inflater = LayoutInflater.from(context)

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 7
    override fun isEnabled(position: Int) = rows.getOrNull(position).let { it is Row.Value || it is Row.Message }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> 0
        is Row.Note -> 1
        is Row.Value -> 2
        is Row.Message -> 3
        is Row.Code -> 4
        is Row.Action -> 5
        is Row.Picture -> 6
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val row = rows[position]) {
            is Row.Header -> text(convertView, parent, R.layout.row_header, row.text)
            is Row.Note -> text(convertView, parent, R.layout.row_note, row.text)
            is Row.Value -> value(convertView, parent, row)
            is Row.Message -> message(convertView, parent, row)
            is Row.Code -> code(convertView, parent, row)
            is Row.Action -> action(convertView, parent, row)
            is Row.Picture -> picture(convertView, parent, row)
        }

    private fun text(convertView: View?, parent: ViewGroup, layout: Int, text: String): View {
        val view = convertView ?: inflater.inflate(layout, parent, false)
        (view as TextView).text = text
        return view
    }

    private fun value(convertView: View?, parent: ViewGroup, row: Row.Value): View {
        val view = convertView ?: inflater.inflate(R.layout.row_value, parent, false)
        view.setBackgroundResource(if (row.highlighted) R.drawable.bg_row_highlight else R.drawable.bg_row)
        view.findViewById<TextView>(R.id.label).text = row.label
        val tag = view.findViewById<TextView>(R.id.tag)
        tag.visibility = if (row.tag == null) View.GONE else View.VISIBLE
        tag.text = row.tag
        tag.setTextColor(context.getColor(row.tagColor))
        view.findViewById<View>(R.id.labelRow).visibility =
            if (row.label.isEmpty() && row.tag == null) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.value).text = row.value
        return view
    }

    private fun message(convertView: View?, parent: ViewGroup, row: Row.Message): View {
        val view = convertView ?: inflater.inflate(R.layout.row_message, parent, false)
        view.setBackgroundResource(if (row.mine) R.drawable.bg_row_highlight else R.drawable.bg_row)
        val label = view.findViewById<TextView>(R.id.label)
        label.text = row.label
        label.visibility = if (row.label.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.text).text = row.text
        return view
    }

    private fun code(convertView: View?, parent: ViewGroup, row: Row.Code): View {
        val view = convertView ?: inflater.inflate(R.layout.row_code, parent, false)
        view.findViewById<TextView>(R.id.language).text = row.language
        view.findViewById<TextView>(R.id.code).text = row.code
        view.findViewById<Button>(R.id.copy).setOnClickListener { Actions.copy(context, row.code) }
        return view
    }

    private fun action(convertView: View?, parent: ViewGroup, row: Row.Action): View {
        val view = convertView ?: inflater.inflate(R.layout.row_action, parent, false)
        view.setBackgroundResource(if (row.highlighted) R.drawable.bg_row_highlight else R.drawable.bg_row)
        view.findViewById<TextView>(R.id.title).text = row.title
        val detail = view.findViewById<TextView>(R.id.detail)
        detail.text = row.detail
        detail.visibility = if (row.detail.isEmpty()) View.GONE else View.VISIBLE
        val tag = view.findViewById<TextView>(R.id.tag)
        tag.visibility = if (row.tag == null) View.GONE else View.VISIBLE
        tag.text = row.tag
        tag.setTextColor(context.getColor(row.tagColor))
        val bar = view.findViewById<ProgressBar>(R.id.progress)
        if (row.progress != null || row.indeterminate) {
            bar.visibility = View.VISIBLE
            bar.isIndeterminate = row.indeterminate
            bar.max = 100
            bar.progress = row.progress ?: 0
        } else {
            bar.visibility = View.GONE
        }
        view.findViewById<View>(R.id.buttons).visibility = if (row.buttons.isEmpty()) View.GONE else View.VISIBLE
        listOf(R.id.button1, R.id.button2, R.id.button3).forEachIndexed { index, id ->
            val button = view.findViewById<Button>(id)
            val spec = row.buttons.getOrNull(index)
            if (spec == null) {
                button.visibility = View.GONE
                button.setOnClickListener(null)
            } else {
                button.visibility = View.VISIBLE
                button.text = spec.label
                button.isEnabled = spec.enabled
                button.alpha = if (spec.enabled) 1f else 0.4f
                button.setOnClickListener { spec.onClick() }
            }
        }
        return view
    }

    private fun picture(convertView: View?, parent: ViewGroup, row: Row.Picture): View {
        val view = convertView ?: inflater.inflate(R.layout.row_image, parent, false)
        view.findViewById<ImageView>(R.id.image).setImageBitmap(row.bitmap)
        val button = view.findViewById<Button>(R.id.imageButton)
        button.text = row.button.label
        button.isEnabled = row.button.enabled
        button.alpha = if (row.button.enabled) 1f else 0.4f
        button.setOnClickListener { row.button.onClick() }
        return view
    }
}
