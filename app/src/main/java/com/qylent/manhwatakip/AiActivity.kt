package com.qylent.manhwatakip

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import java.util.concurrent.Future

class AiActivity : LibraryActivity() {
    companion object {
        private const val INTRO = "Seri adını/bağlantısını yaz, okuma kaydını anlat veya kişisel öneri iste. AI bilgileri doğrulanmış kaynak değildir."
    }
    class ChatState : ViewModel() {
        var id = UUID.randomUUID().toString()
        var initialized = false
        var draft = ""
        var pending: Future<AiReply>? = null
        var sent = ""
        val history = mutableListOf<Pair<String, String>>()
        val tokens = mutableListOf<String>()
        var answer = INTRO
        // Owned by the application: leaving this screen must not cancel its current request.
    }
    private val state by lazy { ViewModelProvider(this, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = app.chatState as T
    })[ChatState::class.java] }
    private lateinit var message: EditText
    private lateinit var answer: TextView
    private lateinit var conversation: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var cards: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val app get() = application as LibraryApplication
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !text.isNullOrBlank() && state.pending == null) {
            message.setText(text.take(4000)); message.setSelection(message.text.length)
            notice("Metni düzenleyip Gönder'e basabilirsin.")
        } else if (result.resultCode != RESULT_OK) notice("Sesle yazdırma tamamlanmadı; yazılı devam edebilirsin.")
    }
    private val poll = object : Runnable {
        override fun run() {
            val future = state.pending ?: return
            if (!future.isDone) { handler.postDelayed(this, 200); return }
            state.pending = null
            runCatching { future.get() }.fold({ reply ->
                state.history.add("user" to state.sent); state.history.add("assistant" to reply.message)
                state.answer = ""
                state.tokens.forEach { app.proposals.remove(it) }; state.tokens.clear()
                reply.proposals.forEach { proposal ->
                    val token = UUID.randomUUID().toString(); app.proposals[token] = proposal; state.tokens.add(token)
                }
                message.text.clear()
            }, { problem ->
                val cause = (problem as? java.util.concurrent.ExecutionException)?.cause ?: problem
                state.answer = if (cause is IllegalArgumentException) cause.message ?: "AI yanıtı alınamadı." else "AI isteği tamamlanamadı. Hiçbir kayıt değiştirilmedi."
            })
            renderReply(); lock(false)
            saveChat()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("AI sohbeti")
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val column = Ui.scroll(this, page)
        if (landscape) {
            val heading = page.getChildAt(0)
            page.removeView(heading); page.getChildAt(0).visibility = View.GONE
            column.addView(heading)
        }
        val chatActions = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        val newChat = Ui.button(this, "Yeni sohbet") { changeChat(null) }.apply { textSize = 13f }
        val history = Ui.button(this, "Sohbetler") {
            io({ app.chats.list() }) { chats ->
                if (chats.isEmpty()) notice("Henüz kayıtlı sohbet yok.")
                else AlertDialog.Builder(this).setTitle("Sohbetler").setItems(chats.map { it.second }.toTypedArray()) { _, which -> changeChat(chats[which].first) }.show()
            }
        }.apply { textSize = 13f }
        chatActions.addView(newChat, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chatActions.addView(history, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionButtons.addAll(listOf(newChat, history))
        chatActions.addView(Ui.iconButton(this, R.drawable.ic_more, "Sohbet araçları") { showChatTools() }, LinearLayout.LayoutParams(with(Ui) { dp(48) }, with(Ui) { dp(48) }))
        if (landscape) column.addView(chatActions) else page.addView(chatActions, 2)
        column.addView(Ui.button(this, "AI Paylaşımı") {
            if (!busy && state.pending == null) startActivity(Intent(this, ChatGptActivity::class.java))
        }.also { actionButtons.add(it) })
        scroll = column.parent as ScrollView
        conversation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; contentDescription = "Sohbet mesajları" }
        column.addView(conversation)
        answer = Ui.text(this, state.answer, 15f)
        column.addView(answer)
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; column.addView(cards)
        message = Ui.input(this, "Mesaj, seri adı veya bağlantısı", 4000, true).apply { setText(savedInstanceState?.getString("message") ?: state.draft) }
        message.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { state.draft = s.toString(); handler.removeCallbacks(saveDraft); handler.postDelayed(saveDraft, 500) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        message.minLines = if (landscape) 1 else 2
        message.maxLines = if (landscape) 2 else 3
        message.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        val actions = LinearLayout(this)
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.surface()
            setPadding(with(Ui) { dp(4) }, with(Ui) { dp(4) }, with(Ui) { dp(4) }, with(Ui) { dp(4) })
        }
        message.background = null
        if (landscape) actions.addView(message, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)) else composer.addView(message)
        composer.addView(actions); page.addView(composer)
        val voice = Ui.iconButton(this, R.drawable.ic_microphone, "Sesle yazdır") {
            if (state.pending != null || busy) return@iconButton
            try { speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR").putExtra(RecognizerIntent.EXTRA_PROMPT, "Metin gönderilmeden düzenlenebilir")) }
            catch (_: Exception) { notice("Ses tanıma hizmeti bulunamadı; yazılı devam edebilirsin.") }
        }
        voice.tag = "chatVoice"
        actions.addView(voice, LinearLayout.LayoutParams(with(Ui) { dp(48) }, with(Ui) { dp(48) }))
        if (!landscape) actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val send = Ui.button(this, "Gönder", true) { send() }.apply { textSize = 14f }
        actions.addView(send, if (landscape) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, with(Ui) { dp(48) }))
        actionButtons.add(send)
        page.addView(LibraryNavigation.create(this, LibraryNavigation.Destination.CHAT) { !busy && state.pending == null })
        renderReply(); lock(state.pending != null)
        if (!state.initialized) io({ app.chats.active() }) { saved ->
            if (saved != null) restoreChat(saved)
            state.initialized = true; lock(state.pending != null)
        }
    }

    private fun showChatTools() {
        if (state.pending != null || busy) return
        AlertDialog.Builder(this).setTitle("Sohbet araçları")
            .setItems(arrayOf("Seri bilgisi hazırla", "Kapak ara", "Kişisel öneri iste", "Ayarlar")) { _, which ->
                if (state.pending != null || busy) return@setItems
                when (which) {
                    0 -> {
                        val value = message.text.toString()
                        if (value.isBlank()) notice("Önce seri adı veya bağlantısını yaz.")
                        else message.setText("Bu ad/bağlantı için seri bilgisi ve belirsizse ayrı adaylar hazırla: $value".take(4000))
                    }
                    1 -> io({ store.all() }) { series ->
                        if (series.isEmpty()) notice("Önce kütüphaneye bir seri ekle.")
                        else AlertDialog.Builder(this).setTitle("Kapak aranacak seri")
                            .setItems(series.map { it.title }.toTypedArray()) { _, index ->
                                if (state.pending == null && !busy) startActivity(Intent(this, EditActivity::class.java)
                                    .putExtra("seriesId", series[index].id).putExtra("findTurkishCover", true))
                            }.show()
                    }
                    2 -> message.setText("Kütüphanemdeki beğenilerim, sevme/sevmeme ve bırakma nedenlerime göre kişisel manhwa önerileri ver. ${message.text}".take(4000))
                    3 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }.show()
    }

    private fun send() {
        if (state.pending != null || busy) return
        val text = message.text.toString()
        if (text.isBlank()) { notice("Önce mesajını yaz."); return }
        io({ AiSecretStore(this).let { it.settings() to it.consented() } }) { (settings, consented) ->
            if (settings == null) { notice("Önce AI ayarlarını kaydet."); startActivity(Intent(this, AiSettingsActivity::class.java)); return@io }
            if (consented) launch(text)
            else AlertDialog.Builder(this).setTitle("AI servisine veri paylaşımı")
                .setMessage("Mesajların ve mevcut kütüphanendeki seri adları/bağlantıları, bölüm, durum, özel kategoriler, beğeni, nedenler ve kişisel notlar ${settings.baseUrl} adresindeki ${settings.model} modeline gönderilecek. ${if (settings.isLocalHttp) "Bu yerel HTTP bağlantısında veriler ve varsa API anahtarı ağda şifrelenmeden taşınır. " else ""}Sesle yazdırma ayrı Android hizmetini kullanır ve çevrimdışı olması garanti değildir. Kayıtlar yalnızca sen onayladığında değişir.")
                .setNegativeButton("İptal", null).setPositiveButton("Paylaş ve gönder") { _, _ -> io({ AiSecretStore(this).consent(settings) }) { launch(text) } }.show()
        }
    }

    private fun launch(text: String) {
        if (state.pending != null) return
        state.sent = text
        val history = state.history.toList()
        state.pending = app.networkExecutor.submit<AiReply> {
            val secrets = AiSecretStore(this)
            val connection = secrets.connection() ?: throw IllegalArgumentException("Önce AI ayarlarını kaydet.")
            require(connection.consented) { "Önce veri paylaşımını onayla." }
            val snapshot = app.executor.submit<List<Series>> { app.store.all() }.get()
            AiClient().chat(connection.settings, connection.key, text, snapshot, history)
        }
        state.answer = "AI yanıtı bekleniyor… Kayıtların değişmedi."; renderReply()
        saveChat()
        lock(true); handler.post(poll)
    }

    private fun lock(active: Boolean) {
        message.isEnabled = !active && !busy; actionButtons.forEach { it.isEnabled = !active && !busy }
        page.findViewWithTag<android.view.View>("chatVoice")?.isEnabled = !active && !busy
    }

    private fun renderMessage(role: String, content: String): android.view.View = Ui.text(this,
        "${if (role == "user") "Sen" else "AI"}\n$content", 15f).apply {
        if (role == "user") {
            background = Ui.surface(Ui.userBubble)
            maxWidth = with(Ui) { dp((resources.configuration.screenWidthDp - 40) * 85 / 100) }
        }
        setLineSpacing(with(Ui) { dp(3) }.toFloat(), 1f)
        setPadding(with(Ui) { dp(if (role == "user") 14 else 4) }, with(Ui) { dp(12) }, with(Ui) { dp(14) }, with(Ui) { dp(12) })
        setTextIsSelectable(true)
    }
    override fun onBusyChanged(active: Boolean) { if (::message.isInitialized) lock(active || state.pending != null) }
    private fun renderReply() {
        conversation.removeAllViews()
        val messages = state.history.toList() + if (state.pending != null) listOf("user" to state.sent) else emptyList()
        messages.forEach { (role, content) ->
            conversation.addView(renderMessage(role, content), LinearLayout.LayoutParams(
                if (role == "user") ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = with(Ui) { dp(18) }
                gravity = if (role == "user") Gravity.END else Gravity.START
            })
        }
        answer.text = state.answer
        answer.setTextColor(if (state.answer == INTRO) Ui.muted else Ui.ink)
        answer.textSize = if (state.answer == INTRO) 13f else 15f
        answer.visibility = if (state.answer.isBlank() || messages.isNotEmpty() && state.answer == INTRO) View.GONE else View.VISIBLE
        cards.removeAllViews()
        state.tokens.toList().forEach { token ->
            val proposal = app.proposals[token] ?: return@forEach
            Ui.gap(this, cards)
            cards.addView(Ui.text(this, "Doğrulanmamış aday: ${proposal.after.title}\n\n${AiProtocol.preview(proposal)}", 14f))
            val review = Ui.button(this, "Seç ve düzenle: ${proposal.after.title}") { startActivity(Intent(this, EditActivity::class.java).putExtra("proposalId", token)) }
            review.isEnabled = state.pending == null
            cards.addView(review)
            cards.addView(Ui.button(this, "Reddet: ${proposal.after.title}") { app.proposals.remove(token); state.tokens.remove(token); renderReply(); saveChat() }.apply { isEnabled = state.pending == null })
        }
        // Scroll the conversation without moving focus away from the composer/IME.
        scroll.post { scroll.scrollTo(0, scroll.getChildAt(0).height) }
    }
    override fun onResume() { super.onResume(); renderReply(); lock(state.pending != null); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); handler.removeCallbacks(saveDraft); saveChat(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("message", message.text.toString()); super.onSaveInstanceState(outState) }
    private val saveDraft = Runnable { saveChat() }
    private fun snapshot() = ChatRecord(state.id, state.history.toList(), message.text.toString(),
        if (state.pending != null) "Önceki AI isteği kesildi. Taslağını inceleyip yeniden gönderebilirsin; hiçbir kayıt değiştirilmedi." else state.answer,
        state.tokens.mapNotNull { token -> app.proposals[token]?.let { BridgePending(token, it) } })
    private fun saveChat() {
        if (!state.initialized || !::message.isInitialized) return
        val chat = snapshot()
        app.executor.execute { runCatching { app.chats.save(chat) }.onFailure { runOnUiThread { notice("Sohbet kaydedilemedi; mevcut ekran korunuyor.") } } }
    }
    private fun restoreChat(chat: ChatRecord) {
        state.tokens.forEach { app.proposals.remove(it) }; state.tokens.clear()
        state.id = chat.id; state.history.clear(); state.history.addAll(chat.history)
        state.draft = chat.draft; state.answer = chat.answer
        chat.proposals.forEach { app.proposals[it.token] = it.proposal; state.tokens.add(it.token) }
        message.setText(chat.draft); renderReply()
    }
    private fun changeChat(id: String?) {
        if (busy || state.pending != null || !state.initialized) return
        handler.removeCallbacks(saveDraft)
        val current = snapshot()
        io({
            app.chats.save(current)
            val next = if (id == null) ChatRecord() else app.chats.get(id) ?: throw IllegalArgumentException("Sohbet bulunamadı.")
            app.chats.save(next); next
        }) { restoreChat(it) }
    }
}
