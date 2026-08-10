package com.tasirin.httpdownloadmanager.util

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Spinner

/** Pasang ArrayAdapter standar pada Spinner: item dialog sederhana + daftar
 *  dropdown. Satu pola untuk semua spinner (dialog & pengaturan). */
fun setupSpinner(
    context: Context,
    spinner: Spinner,
    options: List<String>
): ArrayAdapter<String> {
    val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, options)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
    return adapter
}
