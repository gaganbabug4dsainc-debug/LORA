# LoRA (v0.6)

Android app: Accessibility Service se phone chalane wala **Agent**, phone me rehne wali **offline Chat**, floating **mini window + voice assistant**, aur model-independent **resumable tasks**.
(applicationId purana hi hai, isliye purane version par seedha update hota hai; keys/settings bachti hain.)

## v0.6: naya design + phone ke andar ka model
- **Naya Home screen**: status card (progress bar, Pause/Resume/Skip/Next/Stop, Retry/Restart/Steps/Checkpoint), goal composer + templates, setup checklist, shortcuts (Chat, Skill Studio, History, Doosre AI), haal ke tasks + stats, live log.
- **Settings alag screen** (sections tap karke kholo): Agent & steps, Privacy, Voice, Models & Providers, Offline models, Backup/Restore, Floating icon, Log.
- **Phone ke andar ka model (MediaPipe)**: Settings -> Offline models -> `.task` / `.litertlm` file chuno (Gemma/Qwen, chhota). Internet/quota nahi lagta; Model Manager me 🟢 offline dikhta hai aur Auto me sabse pehle use hota hai. (Ollama server wala tareeka bhi hai.)
- **Backup / Restore** (encrypted, optional passphrase): settings + skills (+ chats). Keys kabhi nahi jaati.
- **Chat text ab encrypted** (Android Keystore) -- purane plain messages bhi padhe jaate hain.
- Floating panel me **⟲ Restart** (confirm ke saath).
- Build ke liye `android.useAndroidX=true` (gradle.properties) zaruri hai.
- Limits: device model dheema ho sakta hai (Snapdragon 695), Stop par jawab poora hone tak ruka rehta hai, chat template file ke naam (gemma/qwen) se pehchana jata hai; nayi cheezein pehli baar test hoti hain -- build/crash ho to log bhejo.

## v0.4 me kya naya hai
**Floating**
- Floating icon: drag = kahin bhi le jao | tap = mini panel | double tap = Agent Chat. Rang: 🟢 Running, 🟡 Waiting, 🔵 Paused, 🔴 Error, ⚪ Idle.
- Stop: laal ■ floating button (agent chalte waqt hamesha dikhta hai, drag hota hai) + panel me ■ STOP. Safe stop: naye actions band -> state + checkpoint save -> Resume ho sakta hai.
- Mini window: header se drag, ◢ se resize, ▾ collapse, ⤢ maximize, — icon me wapas, ✕ close. Jagah/size yaad rehte hain.
- Controls: ⏸ Pause, ▶ Resume, Skip, Next (ek step chalao phir ruko), Retry, +Step, 📋 Steps (editor), ⚑ Checkpoint, Limit (10/20/50/100/200/∞), ✋ Manual.

**Agent Chat + Voice**
- Panel ka 🤖 Agent mode = chalte task se baat. "pause", "continue", "skip this step", "next", "go back", "retry", "restart", "stop", "status", "what are you doing", "change the goal", "continue from last checkpoint" (Hindi/Hinglish bhi: ruko, chalo, chhod do, agla, piche jao, kya kar rahe ho) local chalte hain, LLM ke bina.
- Sawal poochho to jawab task ki poori state dekhkar aata hai (goal, step, history, pending steps, checkpoint, model). Normal Chat se bhi agent control hota hai ("pause my running agent") aur Chat me task bar dikhta hai.
- 🎤 se bolo; 🎙 Live = bolo -> jawab suno -> phir sunna. Speech: Silent / sirf zaruri / har step; speed, volume, voice, bhasha (Hindi/English).

**Steps**
- Koi hard 15-step limit nahi. Default **Unlimited**; ya 10/20/50/100/200/custom. Limit par poochta hai "aur chalaun?".
- Loop protection: same action 5 baar, 8 baar wait, ya A-B-A-B chakkar -> "Continue / Plan badlo / Stop".
- User ke steps: aage ke (pending) steps jodo (agla ya aakhir me), edit, delete, upar/neeche, duplicate, disable, checkpoint banao. Pura ho chuka step badla nahi jata.
- Auto checkpoint: har N step, risky action se pehle, app badalne se pehle, model badalne se pehle, aur safe stop par.

**State, model switch, recovery**
- Poora state (goal, steps, pending, checkpoints, files, instructions, errors) phone ke SQLite me, kisi model me nahi. Har action ke baad auto-save.
- Model badlo (chat/panel/app) -> "Task state safe hai, Step N se continue" confirmation -> naya model wahin se chalta hai, Step 1 se nahi.
- App crash/band ho to khulne par: "Adhura task mila: Continue / Restart / Delete".
- Task poora hone par agent band nahi hota: "Task completed. Ab kya karun?" -> Continue / Run again / Skill / Review / Close. Skills app me save rehti hain aur dobara chal sakti hain.

**Offline + data local**
- Data Processing: 🟢 Sirf Local | ❓ Online se pehle poochho (default) | 🌐 Online allowed. Local model hamesha pehle; online sirf fallback ya permission ke baad.
- Header me 🟢 Local / Offline ya 🌐 Online dikhta hai.
- Chat history, images, files, tasks, skills phone me. API keys Android Keystore se encrypted.
- Attach: image, camera photo (Android 10+; photo gallery ke `Pictures/LoRA` me bhi rehti hai), TXT/code, **DOCX/XLSX/PPTX** (text phone par hi nikalta hai), **PDF** (pehle 4 page image ban kar vision model ko jate hain; text extract nahi hota).
- "Use this PDF for the current task" jaisa likho to file/image chalte task ke context me jud jati hai.

**Model Manager** (⚙ chip ya app me): har model par Local/Online, Text, Vision, Context size, Available/limit, current. Vision/context sirf provider ki list ya naam se pata chalta hai; "—" ka matlab pata nahi/nahi hai.

## v0.5: Skill Studio, Agent History, doosre AI apps
**🧩 Skill Studio** (app, floating window ka 🧩 chip, ya History se)
- Skill banao: khali, **AI se** (kaam likho + platform), **Task → Skill** (purane task ke safal steps chuno), ya **📥 Paste** (clipboard se import).
- Edit + **Update** (version +1) ya **Naya banao**; **🤖 AI se saaf karo** (steps general, {placeholder} wali values); **▶ Test run**; **Copy** (export text, doosre phone par Paste se import).
- **Merge**: 2+ skills chuno -> Simple (duplicate hata kar jodo) ya AI se merge -> editor me check karke save.
- Duplicate, delete, search (naam/platform). Platform tag (ChatGPT, Gemini, WhatsApp...). `{sawal}` jaisi values run se pehle poochhi jati hain.
- Task poora hone par floating window ka **Skill** chip seedha skill bana deta hai (platform khud pehchanta hai).

**🕘 Agent History** (app ya floating 🕘)
- Saare tasks: kholo, **steps edit / copy / delete**, goal aur instructions edit, **Copy all** (poora task text), **Duplicate**, **Resume**, aage ke steps ka editor, task hamesha ke liye hatao.
- **🧩 Skill banao** ya **↻ Skill update** (kisi maujooda skill ke steps is task ke safal steps se badlo).

**🤖 Doosre AI app (ChatGPT, Gemini, Claude, Copilot, Perplexity, DeepSeek) se chat**
- App ke "Doosre AI app se poochho" ya Skill Studio ke 🤖 se: app chuno + sawal likho -> agent app kholta hai, naya chat, sawal type, jawab poora likhne tak wait, phir screen se jawab padhkar batata hai.
- Task poora hone par **Copy** chip jawab copy karta hai; History me bhi poora jawab rehta hai.
- **📦 Starter skills**: installed AI apps ke liye "Ask <app>" skills ek tap me.
- Dhyan: sawal us app ke server ko jata hai (LoRA ki Data Processing setting ke bahar). Un apps ke automation/terms khud check karo.

**Limits (v0.5)**: AI wale skill tools ke liye ek chalta model chahiye (local ya online-permission ke baad). ChatGPT/Gemini ke steps/hints generic hain, in apps par test nahi hua; app ka UI badle ya wo screen text accessibility ko na de to agent atak sakta hai. Aise me ek baar asli run karke Task -> Skill / Skill update karo. Skills phones ke beech sirf Copy/Paste se jati hain (file backup abhi nahi).

## GitHub Actions se APK
1. Naya repo -> is folder ke andar ki saari files (`.github` samet) upload.
2. Actions -> "Build APK". Artifact `lora-debug-apk` -> `app-debug.apk` install.
3. Android 13+: App info -> ⋮ -> "Allow restricted settings", phir Accessibility me LoRA on.

## Offline model kaise (app me AI model bundled nahi hai)
Termux me Ollama/llama.cpp server chalao -> app -> "Local / offline model" -> `http://127.0.0.1:11434/v1` + model naam. (`http://` sirf localhost/127.0.0.1/10.0.2.2; dusre server ke liye `https://`.) Local provider ko list me upar rakho.

## Abhi nahi hai / limits
- Alag voice model (STT/TTS model) config nahi: sunna/bolna phone ka apna engine karta hai.
- Backup export/import (encrypted) abhi nahi.
- Chat/task database encrypted nahi (sirf keys encrypted); Room ki jagah SQLite.
- "Previous step" aur Restart ka button nahi; Restart/"go back" command se.
- Device reboot ke baad task apne aap resume nahi hota; app kholne par Continue ka option aata hai.
- Screen-sharing ke saath alag se test nahi hua; overlay windows share ke upar bhi dikhni chahiye.
- Voice input Google voice dialog se hota hai (floating window me ek second ke liye helper screen aati hai).
- Is build me acceptance tests nahi chale; debug APK hai.
