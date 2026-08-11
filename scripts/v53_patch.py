from pathlib import Path

p = Path('app/src/main/java/com/huseyn/elixircollector/AutoOverlayService.java')
s = p.read_text()
old = '@Override public void onCreate(){super.onCreate();cv=getSharedPreferences(AutoCaptureService.PREFS,MODE_PRIVATE);createChannel();restoreState();}'
new = '@Override public void onCreate(){super.onCreate();cv=getSharedPreferences(AutoCaptureService.PREFS,MODE_PRIVATE);createChannel();if(cv.getBoolean(AutoCaptureService.K_CAPTURE,false)&&cv.getBoolean(AutoCaptureService.K_MATCH,false))restoreState();else{state.reset();getSharedPreferences(STATE_PREFS,MODE_PRIVATE).edit().clear().apply();}}'
assert old in s
s = s.replace(old, new)
old = 'if(opponentText!=null)opponentText.setText(Double.isNaN(enemy)?"💧 ?":String.format(Locale.US,"💧 %.1f",enemy));'
new = 'if(opponentText!=null)opponentText.setText(!match||Double.isNaN(enemy)?"💧 ?":String.format(Locale.US,"💧 %.1f",enemy));'
assert old in s
s = s.replace(old, new)
s = s.replace('RoyaleVision Auto v5.2', 'RoyaleVision Auto v5.3')
p.write_text(s)

p = Path('app/src/main/java/com/huseyn/elixircollector/AutoMainActivity.java')
s = p.read_text()
old = 'private void beginCapture(){startOverlayService();MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);startActivityForResult(m.createScreenCaptureIntent(),REQ_CAPTURE);}'
new = 'private void beginCapture(){stopService(new Intent(this,AutoOverlayService.class));getSharedPreferences(AutoCaptureService.PREFS,MODE_PRIVATE).edit().putBoolean(AutoCaptureService.K_CAPTURE,false).putBoolean(AutoCaptureService.K_MATCH,false).remove(AutoCaptureService.K_LOCAL_ELIXIR).putString(AutoCaptureService.K_STATUS,"STARTING FRESH").apply();startOverlayService();MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);startActivityForResult(m.createScreenCaptureIntent(),REQ_CAPTURE);}'
assert old in s
s = s.replace(old, new)
s = s.replace('ROYALEVISION AUTO v5.2', 'ROYALEVISION AUTO v5.3')
s = s.replace('so v5.2 keeps', 'so v5.3 keeps')
p.write_text(s)

p = Path('app/src/main/java/com/huseyn/elixircollector/AutoCaptureService.java')
s = p.read_text()
old = 'private static final String CHANNEL="royalevision_auto_capture";private static final int NOTIFICATION_ID=9505;private static final long ANALYZE_EVERY_NS=80_000_000L;private static final int START_STABLE_FRAMES=8,END_MISSING_FRAMES=30;'
new = 'private static final String CHANNEL="royalevision_auto_capture";private static final int NOTIFICATION_ID=9505;private static final long ANALYZE_EVERY_NS=80_000_000L;private static final int START_STABLE_FRAMES=12,END_MISSING_FRAMES=30;'
assert old in s
s = s.replace(old, new)
old = 'private void startCaptureForeground(){Notification n=buildNotification();if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION|ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);else startForeground(NOTIFICATION_ID,n);}'
new = 'private void startCaptureForeground(){Notification n=buildNotification();if(Build.VERSION.SDK_INT>=29){try{startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION|ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);}catch(RuntimeException audioFgsError){prefs.edit().putBoolean(K_AUDIO_AVAILABLE,false).putString(K_STATUS,"AUDIO DISABLED • VISUAL TRACKING ACTIVE").apply();startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);}}else startForeground(NOTIFICATION_ID,n);}'
assert old in s
s = s.replace(old, new)
old = 'boolean reliable=r.battleHud&&!Double.isNaN(r.localElixir)&&r.localElixir>=0.35&&r.elixirConfidence>=0.34;'
new = 'int recognizedHand=0;if(hand!=null){for(int i=0;i<hand.slotIds.length;i++)if(hand.slotIds[i]!=null&&hand.confidence[i]>=0.45)recognizedHand++;}boolean reliable=r.battleHud&&!Double.isNaN(r.localElixir)&&r.localElixir>=0.35&&r.elixirConfidence>=0.34&&recognizedHand>=2;'
assert old in s
s = s.replace(old, new)
old = 'clearLiveDetectionState();handRecognizer.reloadDeck();'
new = 'clearLiveDetectionState();handRecognizer.reloadDeck();prefs.edit().remove(K_ENEMY_EVENT_MS).remove(K_ENEMY_COST).remove(K_ENEMY_CARD_ID).remove(K_ENEMY_CONF).remove(K_LOCAL_CARD_ID).putBoolean(K_MATCH,false).remove(K_LOCAL_ELIXIR).apply();'
assert old in s
s = s.replace(old, new)
s = s.replace('RoyaleVision Auto v5.2', 'RoyaleVision Auto v5.3')
p.write_text(s)

p = Path('app/build.gradle')
s = p.read_text().replace('versionCode 521', 'versionCode 530').replace("versionName '5.2.1'", "versionName '5.3.0'")
p.write_text(s)
