package com.team.clicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val REQ_PROJECTION = 1
        const val PREFS = "clicker"
    }

    private lateinit var energyCheck: CheckBox
    private lateinit var energyMin: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "Кликер. Сначала выдайте разрешения (по порядку), потом запустите бота."
        })

        root.addView(button("1. Показ поверх других окон") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("2. Служба специальных возможностей") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        energyCheck = CheckBox(this).apply {
            text = "Проверять энергию"
            isChecked = prefs.getBoolean("energyCheck", false)
        }
        energyMin = EditText(this).apply {
            hint = "Минимум энергии, например 100"
            setText(prefs.getInt("energyMin", 100).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(energyCheck)
        root.addView(energyMin)
        root.addView(button("Сохранить настройки") {
            prefs.edit()
                .putBoolean("energyCheck", energyCheck.isChecked)
                .putInt("energyMin", energyMin.text.toString().toIntOrNull() ?: 100)
                .apply()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        })

        root.addView(button("3. Запустить бота") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала дайте разрешение на показ поверх окон", Toast.LENGTH_LONG).show()
                return@button
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
        })
        root.addView(button("Остановить бота") {
            stopService(Intent(this, BotService::class.java))
        })

        setContentView(root)
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, BotService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            Toast.makeText(this, "Бот запущен. Панель на экране", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else if (requestCode == REQ_PROJECTION) {
            Toast.makeText(this, "Нужно разрешение на захват экрана", Toast.LENGTH_LONG).show()
        }
    }
}
