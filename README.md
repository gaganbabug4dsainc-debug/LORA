# Phone Agent (v0.3)

Android app jo Accessibility Service se dusre apps ki screen padhta hai aur tap/type/scroll karta hai.
Dimaag: cloud LLM providers (Gemini / OpenRouter / Groq / custom), auto-switch ke saath.
Local model, voice, offline chat, file/image upload: Phase 2.

## GitHub Actions se APK banao (PC ki zarurat nahi)
1. GitHub par repo banao aur is zip ke saare files upload karo (`.github` folder samet). Branch `main`.
2. **Actions** tab -> "Build APK" chalne do (ya "Run workflow").
3. Artifacts me `phone-agent-debug-apk` download karo, zip kholo, `app-debug.apk` install karo.

## Phone par setup
1. APK install karo. Android 13+: Settings -> Apps -> Phone Agent -> ⋮ -> **Allow restricted settings**.
2. App kholo -> "Accessibility settings kholo" -> **Phone Agent** on karo.
3. Providers me API key jodo (ek se zyada jodoge to limit aane par agla apne aap chalega).
4. Chat box me goal likho -> **Bhejo / Start**.

## v0.3 me kya naya hai
- **15-step limit hata diya.** Settings -> Agent Step Limit: 10/20/50/100/200/Custom/♾ Unlimited (default 100).
  Limit poori hone par agent poochta hai: "+50 steps" ya Stop. Unlimited me bhi loop-protection hai.
- **Loop protection:** wahi steps baar-baar dohrane par "Continue / Stop / Change Plan" poochta hai.
- **Floating controls:**
  - Draggable **AI** icon (rang = status: grey idle, green running, peela waiting, neela paused, laal error;
    running me icon par step number). Tap = mini window, double tap = chat input ke saath.
  - Alag draggable laal **■ Stop** button, jab tak agent chal raha ho hamesha screen par.
  - Asli floating **mini window**: header se move, neeche-daayein ◢ se resize, ▁ se collapse, ✕ se band.
    Position/size yaad rehte hain. Panel neeche ke app ka focus tabhi leta hai jab tum chat box me type karo.
  - Controls: Pause/Resume, Skip, Next, Stop, Checkpoint, Retry, + Step, Model, Chat.
- **Agent Chat:** panel/dashboard se likho. Chhote commands (stop, pause, continue, skip, next, retry, status,
  checkpoint, change goal; Hinglish bhi) local chalte hain, model ke bina. Baaki message agent ko instruction
  ban jata hai. Chal nahi raha ho to naya goal.
- **Model-independent task state:** goal, step, history, notes, queue, checkpoints, errors disk par
  (`task.json`) har step ke baad save hote hain. Model badalne par task reset nahi hota.
- **Model Manager:** Models section me list, Online/Offline badge, "Use" se manual switch
  (task chal raha ho to confirm poochta hai). Auto mode me pehla available chalta hai, fail hone par agla.
- **Crash/restart recovery:** app kholne par adhoora task mile to Continue / Restart / Delete.
  Stop karne par bhi state save hota hai; Resume se wahin se aage.
- **Checkpoints:** manual, auto (har N steps), risky action se pehle, pause/stop/model switch par.
- **Steps queue:** user steps add/edit/delete/move/duplicate/disable/checkpoint-marker, agent chalte waqt bhi.
- **Data Processing:** Local Only / Ask Before Online (default) / Allow Online.
  Ask mode me har naye task par ek baar poochta hai ki screen text online model ko bheja jaye ya nahi.

## Safety
- Send/delete/pay jaise actions se pehle Allow/Deny dialog. Password fields ka text model ko nahi jata.
- Stop: naye actions band, state save, checkpoint, baad me Resume.
- Bank/payment/OTP apps par mat chalao.

## Abhi nahi hai (Phase 2)
Voice input/output (STT/TTS), local on-device model, offline chat + file/image upload, Room database,
encrypted backup, Skills/Workflows.

## v0.4 (upgrade)
- Floating panel: mic, back, chat, app; voice mode chip. Normal Chat + Aur settings screens.
- Local (offline) model via MediaPipe, voice (STT/TTS), skills, encrypted storage, backup/restore.
- NOTE: compile/test sirf GitHub Actions + phone par hoga; pehle build error check karo.
