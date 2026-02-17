package com.example.systemrpg

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.*
import kotlin.concurrent.timer

class MainActivity : AppCompatActivity() {

    // Native metodlarımızı tanımlıyoruz
    private external fun herSeyiBaslat()
    private external fun saldiraBasildi()
    private external fun ekraniGuncelle(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dinamik UI: Kodla basit bir ekran oluşturuyoruz
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(20, 20, 20, 20)
        layout.setBackgroundColor(android.graphics.Color.BLACK)

        val display = TextView(this)
        display.setTextColor(android.graphics.Color.GREEN)
        display.textSize = 14f
        display.typeface = android.graphics.Typeface.MONOSPACE

        val attackBtn = Button(this)
        attackBtn.text = "ATTACK SYSTEM"

        layout.addView(display)
        layout.addView(attackBtn)
        setContentView(layout)

        // Oyunu başlat
        herSeyiBaslat()

        // Buton dinleyicisi
        attackBtn.setOnClickListener {
            saldiraBasildi()
        }

        // Ekranı her 100ms'de bir C'den gelen verilerle tazele
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val currentFrame = ekraniGuncelle()
                runOnUiThread {
                    display.text = currentFrame
                }
            }
        }, 0, 100)
    }

    companion object {
        init {
            System.loadLibrary("main")
        }
    }
}
