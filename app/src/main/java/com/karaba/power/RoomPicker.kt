package com.karaba.power

import android.app.Activity
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Choosing, adding, renaming and deleting rooms (e.g. Kitchen, Bathroom). Shared by the quick
 * check and the always-on monitor; each room learns how bright it is on kahraba and on moteur.
 * (In the code and saved data a room is called a "spot".)
 */
object RoomPicker {

    /** Shows the room list. [onChanged] gets the chosen room ("" for none) after any change. */
    fun pick(activity: Activity, current: String, onChanged: (String) -> Unit) {
        val rooms = Prefs.spots(activity)
        val items = listOf("No room") + rooms.map { if (it == current) "$it  ✓" else it } +
            listOf("+ Add a room…") + if (rooms.isNotEmpty()) listOf("Rename or delete…") else emptyList()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Which room are you in?")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    i == 0 -> onChanged("")
                    i <= rooms.size -> onChanged(rooms[i - 1])
                    i == rooms.size + 1 -> askName(activity, "New room", "") { name ->
                        Prefs.addSpot(activity, name)
                        onChanged(name)
                    }
                    else -> manage(activity) { onChanged(if (current in Prefs.spots(activity)) current else "") }
                }
            }
            .show()
    }

    private fun manage(activity: Activity, onDone: () -> Unit) {
        val rooms = Prefs.spots(activity)
        MaterialAlertDialogBuilder(activity)
            .setTitle("Rename or delete")
            .setItems(rooms.toTypedArray()) { _, i ->
                val name = rooms[i]
                MaterialAlertDialogBuilder(activity)
                    .setTitle(name)
                    .setItems(arrayOf("Rename", "Delete (forget what it learned)")) { _, j ->
                        if (j == 0) {
                            askName(activity, "Rename room", name) { to ->
                                Prefs.renameSpot(activity, name, to)
                                CheckHistory.renameSpot(activity, name, to)
                                onDone()
                            }
                        } else {
                            Prefs.removeSpot(activity, name)
                            CheckHistory.forgetSpot(activity, name)
                            onDone()
                        }
                    }
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun askName(activity: Activity, title: String, initial: String, onDone: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(initial)
            hint = "e.g. Kitchen"
            setSingleLine()
            setSelection(text.length)
        }
        val box = FrameLayout(activity).apply {
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = Prefs.cleanSpotName(input.text.toString())
                if (name.isNotEmpty() && (name == initial || name !in Prefs.spots(activity))) onDone(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** One line describing what a room has learned, for the room cards. */
    fun describe(activity: Activity, room: String, noRoomText: String): String {
        if (room.isEmpty()) return noRoomText
        val (k, m) = CheckHistory.spotLevels(activity, room)
        return when {
            k != null && m != null && k > m * QuickCheck.LEVEL_GAP ->
                "Learned: kahraba ≈ ${k.toInt()} lux, moteur ≈ ${m.toInt()} lux. It can now tell them apart here."
            k != null && m != null ->
                "Kahraba (${k.toInt()} lux) and moteur (${m.toInt()} lux) look too alike here. Try closer to a halogen."
            k == null && m == null ->
                "Not learned yet: confirm one check here on kahraba and one on moteur."
            k == null -> "Moteur ≈ ${m!!.toInt()} lux. Still needs one confirmed kahraba."
            else -> "Kahraba ≈ ${k.toInt()} lux. Still needs one moteur."
        }
    }
}
