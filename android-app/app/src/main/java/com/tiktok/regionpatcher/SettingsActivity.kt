package com.tiktok.regionpatcher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tiktok.regionpatcher.core.RegionPreset
import com.tiktok.regionpatcher.core.RegionPreferences
import com.tiktok.regionpatcher.core.RegionPresets
import com.tiktok.regionpatcher.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var adapter: RegionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = RegionAdapter(
            items = RegionPresets.all,
            selectedIdProvider = { RegionPreferences.getPresetId(this) },
        ) { preset ->
            RegionPreferences.setPresetId(this, preset.id)
            adapter.notifyDataSetChanged()
        }
        binding.regionList.layoutManager = LinearLayoutManager(this)
        binding.regionList.adapter = adapter
    }

    private class RegionAdapter(
        private val items: List<RegionPreset>,
        private val selectedIdProvider: () -> String,
        private val onSelect: (RegionPreset) -> Unit,
    ) : RecyclerView.Adapter<RegionAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_region_preset, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val preset = items[position]
            holder.bind(preset, preset.id == selectedIdProvider(), onSelect)
        }

        override fun getItemCount(): Int = items.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val radio: RadioButton = itemView.findViewById(R.id.regionRadio)
            private val title: TextView = itemView.findViewById(R.id.regionTitle)
            private val subtitle: TextView = itemView.findViewById(R.id.regionSubtitle)

            fun bind(preset: RegionPreset, selected: Boolean, onSelect: (RegionPreset) -> Unit) {
                title.text = "${preset.displayName} (${preset.countryIso})"
                subtitle.text = "${preset.operatorName} · MCC+MNC ${preset.operator}"
                radio.isChecked = selected
                itemView.setOnClickListener { onSelect(preset) }
            }
        }
    }
}
