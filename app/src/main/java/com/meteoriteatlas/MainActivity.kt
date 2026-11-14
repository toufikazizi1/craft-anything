package com.meteoriteatlas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import android.os.Looper
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*
import android.app.AlertDialog
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.floor

data class Meteorite(val name:String,val place:String,val cls:String,val year:String,val mass:String,val fall:String,val lat:Double,val lon:Double)

data class FireballEvent(
 val dateUtc:String,
 val latitude:Double,
 val longitude:Double,
 val altitudeKm:Double?,
 val radiatedEnergy:Double?,
 val impactEnergyKt:Double?
)

class MainActivity:AppCompatActivity() {
 private lateinit var map:MapView
 private lateinit var status:TextView
 private lateinit var query:EditText
 private lateinit var count:TextView
 private lateinit var fallFilter:Spinner
 private lateinit var mapStyle:Spinner
 private lateinit var offlineButton:Button
 private val worker=Executors.newSingleThreadExecutor()
 private val cache by lazy { File(filesDir,"meteorites.csv") }
 private val officialCache by lazy { File(filesDir,"metbull_official.csv") }
 private val localDb by lazy { MeteoriteDb(this) }
 private val prefs by lazy { getSharedPreferences("atlas",Context.MODE_PRIVATE) }
 private var data=emptyList<Meteorite>()
 private var shown=emptyList<Meteorite>()
 private val mapMarkers=mutableListOf<Marker>()
 private val fireballMarkers=mutableListOf<Marker>()
 private var fireballEvents=emptyList<FireballEvent>()
 private val fireballCache by lazy { File(filesDir,"nasa_fireballs_cache.json") }
 private var lastZoom=-1
 private var offline=false
 private val styles=listOf("الطرق","الأقمار الصناعية","Hybrid • أقمار + أسماء وطرق","التضاريس")
 private var labelsOverlay:TilesOverlay?=null
 private var myLoc:MyLocationNewOverlay?=null
 private lateinit var alertsBtn:Button
 private val ui=Handler(Looper.getMainLooper())
 private var redrawJob:Runnable?=null

 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val mapRoot=File(filesDir,"osmdroid");mapRoot.mkdirs()
  Configuration.getInstance().setOsmdroidBasePath(mapRoot)
  Configuration.getInstance().setOsmdroidTileCache(File(mapRoot,"tiles"))
  Configuration.getInstance().userAgentValue="$packageName/1.2 (Meteorite Atlas Android)"
  Configuration.getInstance().tileFileSystemCacheMaxBytes=512L*1024*1024
  Configuration.getInstance().tileFileSystemCacheTrimBytes=384L*1024*1024
  offline=prefs.getBoolean("offline",false)
  makeUi()
  setMapStyle(prefs.getInt("style",0))
  map.controller.setZoom(5.0);map.controller.setCenter(GeoPoint(28.0,3.0))
  map.addMapListener(object:MapListener {
   override fun onScroll(e:ScrollEvent?):Boolean{scheduleDraw();return false}
   override fun onZoom(e:ZoomEvent?):Boolean { val z=map.zoomLevel.toInt();if(z!=lastZoom&&data.isNotEmpty()){lastZoom=z;scheduleDraw()};return false }
  })
  load(false)
 }
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 private fun label(t:String,size:Float=13f)=TextView(this).apply{ text=t;textSize=size;setTextColor(Color.WHITE);setPadding(dp(5),dp(3),dp(5),dp(3)) }
 private fun makeUi(){
  val root=LinearLayout(this).apply{orientation=1;setBackgroundColor(Color.rgb(12,25,34))}
  root.addView(TextView(this).apply{text="☄  METEORITE ATLAS • RADAR";textSize=20f;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),0,dp(12),0);setBackgroundColor(Color.rgb(18,48,60))},LinearLayout.LayoutParams(-1,dp(56)))
  val panel=LinearLayout(this).apply{orientation=1;setPadding(dp(9),dp(7),dp(9),dp(4))}
  query=EditText(this).apply{hint="ابحث بالاسم أو المكان أو التصنيف أو السنة";singleLine=true;textSize=14f;setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY);imeOptions=EditorInfo.IME_ACTION_SEARCH;setPadding(dp(12),0,dp(12),0);setBackgroundColor(Color.rgb(31,52,63))}
  panel.addView(query,LinearLayout.LayoutParams(-1,dp(46)))
  val row=LinearLayout(this).apply{orientation=0}
  row.addView(Button(this).apply{text="بحث";setOnClickListener{applyFilter()}},LinearLayout.LayoutParams(0,dp(44),1f))
  row.addView(Button(this).apply{text="تحديث البيانات";setOnClickListener{load(true)}},LinearLayout.LayoutParams(0,dp(44),1f))
  row.addView(Button(this).apply{text="إحصائيات";setOnClickListener{showStats()}},LinearLayout.LayoutParams(0,dp(44),1f))
  row.addView(Button(this).apply{text="العالم";setOnClickListener{query.setText("");fallFilter.setSelection(0);map.controller.setZoom(5.0);map.controller.setCenter(GeoPoint(28.0,3.0));applyFilter()}},LinearLayout.LayoutParams(0,dp(44),1f))
  row.addView(Button(this).apply{text="☄ الرادار";setOnClickListener{refreshFireballs()}},LinearLayout.LayoutParams(0,dp(44),1f))
  panel.addView(row)
  panel.addView(Button(this).apply{text="📍 موقعي (GPS)";setOnClickListener{locate()}},LinearLayout.LayoutParams(-1,dp(44)))
  alertsBtn=Button(this).apply{setOnClickListener{toggleAlerts()}};panel.addView(alertsBtn,LinearLayout.LayoutParams(-1,dp(44)));updateAlertsButton()
  panel.addView(Button(this).apply{text="📋 نيازك جزائرية بلا موقع";setOnClickListener{showNoLocation()}},LinearLayout.LayoutParams(-1,dp(44)))
  panel.addView(Button(this).apply{text="استيراد CSV من Meteoritical Bulletin";setOnClickListener{val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="text/*"};startActivityForResult(i,4102)}},LinearLayout.LayoutParams(-1,dp(42)))
  val filters=LinearLayout(this).apply{orientation=0}
  fallFilter=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("كل الحالات","سقط وشوهد","عُثر عليه"))}
  filters.addView(fallFilter,LinearLayout.LayoutParams(0,dp(42),1f))
  mapStyle=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,styles)}
  filters.addView(mapStyle,LinearLayout.LayoutParams(0,dp(42),1f))
  panel.addView(filters)
  offlineButton=Button(this).apply{setOnClickListener{offline=!offline;prefs.edit().putBoolean("offline",offline).apply();map.setUseDataConnection(!offline);updateOfflineButton();status.text=if(offline)"وضع عدم الاتصال: لن تُحمّل خرائط جديدة؛ المتاح فقط ما سبق تخزينه مؤقتاً وبيانات النيازك المحلية." else "تم تفعيل الاتصال؛ تُحمّل مربعات الخريطة عند عرضها."}}
  panel.addView(offlineButton,LinearLayout.LayoutParams(-1,dp(42)));updateOfflineButton()
  count=label("السجلات: —");panel.addView(count)
  status=label("جارٍ تشغيل الأطلس…");panel.addView(status)
  root.addView(panel)
  map=MapView(this).apply{setMultiTouchControls(true);setBuiltInZoomControls(true);isTilesScaledToDpi=true;setUseDataConnection(!offline)}
  root.addView(map,LinearLayout.LayoutParams(-1,0,1f))
  root.addView(label("© OpenStreetMap contributors • © Esri • © CARTO | التراخيص حسب الطبقة"),LinearLayout.LayoutParams(-1,dp(30)))
  setContentView(root)
  query.setOnEditorActionListener{_,a,_->if(a==EditorInfo.IME_ACTION_SEARCH){applyFilter();true}else false}
  fallFilter.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){if(data.isNotEmpty())applyFilter()}}
  mapStyle.setSelection(prefs.getInt("style",0).coerceIn(0,3))
  mapStyle.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){setMapStyle(pos)}}
 }
 private fun scheduleDraw(){redrawJob?.let{ui.removeCallbacks(it)};val r=Runnable{if(data.isNotEmpty())draw()};redrawJob=r;ui.postDelayed(r,350)}
 private fun locate(){
  if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),5001);return}
  if(myLoc==null){val ov=MyLocationNewOverlay(GpsMyLocationProvider(this),map);myLoc=ov;map.overlays.add(ov)}
  val o=myLoc?:return
  o.enableMyLocation();o.enableFollowLocation();status.text="جارٍ تحديد موقعك بالـGPS… تأكد أن الموقع مفعّل في الهاتف."
  o.runOnFirstFix{runOnUiThread{val p=o.myLocation;if(p!=null){map.controller.setZoom(13.0);map.controller.animateTo(p);status.text="تم تحديد موقعك بالـGPS."}}}
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==5002){if(grantResults.any{it==PackageManager.PERMISSION_GRANTED})startAlerts() else status.text="صلاحية الإشعارات مرفوضة، فلن تصلك التنبيهات."}
  if(requestCode==5001){if(grantResults.any{it==PackageManager.PERMISSION_GRANTED})locate() else status.text="صلاحية الموقع مرفوضة. فعّلها من إعدادات التطبيق."}
 }
 private fun showNoLocation(){
  worker.execute{try{
   val rows=assets.open("no_location.tsv").bufferedReader(Charsets.UTF_8).useLines{ls->ls.filter{it.isNotBlank()}.map{it.split('\t')}.toList()}
   runOnUiThread{
    val labels=rows.map{r->"${r[0]} • ${r.getOrElse(1){""}} • ${r.getOrElse(2){""}} • ${r.getOrElse(3){""}} غ"}.toTypedArray()
    AlertDialog.Builder(this).setTitle("نيازك جزائرية بلا إحداثيات (${rows.size})")
     .setItems(labels){_,i->val r=rows[i];AlertDialog.Builder(this).setTitle(r[0]).setMessage("السنة: ${r.getOrElse(1){""}}\nالتصنيف: ${r.getOrElse(2){""}}\nالكتلة: ${r.getOrElse(3){""}} غرام\nالحالة: ${r.getOrElse(4){""}}\nالموقع: الجزائر (MetBull لا يسجّل لها إحداثيات)").setPositiveButton("إغلاق",null).show()}
     .setNegativeButton("إغلاق",null).show()
   }
  }catch(e:Exception){runOnUiThread{status.text="تعذر فتح القائمة: ${e.message}"}}}
 }
 private fun updateAlertsButton(){alertsBtn.text=if(prefs.getBoolean("alerts",false))"🔔 تنبيهات الجزائر: مفعّلة (اضغط للإيقاف)" else "🔕 تنبيهات الجزائر: متوقفة (اضغط للتفعيل)"}
 private fun toggleAlerts(){
  if(prefs.getBoolean("alerts",false)){WorkManager.getInstance(this).cancelUniqueWork("dz_alerts");prefs.edit().putBoolean("alerts",false).apply();updateAlertsButton();status.text="تم إيقاف التنبيهات.";return}
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),5002);return}
  startAlerts()
 }
 private fun startAlerts(){
  AlertWorker.ensureChannel(this)
  val net=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
  val periodic=PeriodicWorkRequest.Builder(AlertWorker::class.java,15,TimeUnit.MINUTES).setConstraints(net).build()
  val wm=WorkManager.getInstance(this)
  wm.enqueueUniquePeriodicWork("dz_alerts",ExistingPeriodicWorkPolicy.UPDATE,periodic)
  wm.enqueue(OneTimeWorkRequest.Builder(AlertWorker::class.java).setConstraints(net).build())
  prefs.edit().putBoolean("alerts",true).apply();updateAlertsButton()
  status.text="التنبيهات مفعّلة: يفحص التطبيق NASA كل 15 دقيقة تقريبًا (يحتاج إنترنت) وينبّهك لكرة نارية قرب مدن الجزائر."
 }
 private fun updateOfflineButton(){offlineButton.text=if(offline)"● وضع عدم الاتصال: مفعّل" else "● وضع الاتصال: مفعّل"}
 private fun setMapStyle(i:Int){
  if(!::map.isInitialized)return
  labelsOverlay?.let{map.overlays.remove(it);labelsOverlay=null}
  val satellite=object:OnlineTileSourceBase("Esri World Imagery",0,19,256,".jpg",arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")){
   override fun getTileURLString(tile:Long):String { val z=MapTileIndex.getZoom(tile);val x=MapTileIndex.getX(tile);val y=MapTileIndex.getY(tile);return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x" }
  }
  when(i){
   1->map.setTileSource(satellite)
   2->{
    map.setTileSource(satellite)
    val labels=object:OnlineTileSourceBase("Carto Voyager Labels",0,20,256,".png",arrayOf("https://a.basemaps.cartocdn.com/rastertiles/voyager_only_labels/")){
     override fun getTileURLString(tile:Long):String { val z=MapTileIndex.getZoom(tile);val x=MapTileIndex.getX(tile);val y=MapTileIndex.getY(tile);return "https://a.basemaps.cartocdn.com/rastertiles/voyager_only_labels/$z/$x/$y.png" }
    }
    val provider=MapTileProviderBasic(this,labels)
    labelsOverlay=TilesOverlay(provider,this)
    map.overlays.add(labelsOverlay)
   }
   3->map.setTileSource(XYTileSource("OpenTopoMap",0,17,256,".png",arrayOf("https://a.tile.opentopomap.org/","https://b.tile.opentopomap.org/","https://c.tile.opentopomap.org/")))
   else->map.setTileSource(TileSourceFactory.MAPNIK)
  }
  prefs.edit().putInt("style",i).apply();map.invalidate()
 }
 private fun load(force:Boolean){
  if(officialCache.exists() && officialCache.length()>1000){try{val official=parseCsv(officialCache.readText(Charsets.UTF_8));if(official.isNotEmpty()){data=official;localDb.replaceAll(official);applyFilter();status.text="تم تحميل ${official.size} سجلاً من ملف MetBull الرسمي الذي استوردته. لاستبداله، استورد CSV أحدث.";return}}catch(_:Exception){}}
  if(!cache.exists()||cache.length()<1000){try{assets.open("meteorites.csv").use{i->cache.outputStream().use{o->i.copyTo(o)}}}catch(_:Exception){}}
  status.text=if(force)"جارٍ تحديث البيانات من المصدر المتاح…" else "جارٍ تحميل قاعدة النيازك…"
  worker.execute{try{
   if(force||!cache.exists()||cache.length()<1000){
    val tree=JSONObject(get("https://api.github.com/repos/SweetEyesBirb/Meteorite-Landings-Map/git/trees/main?recursive=1"))
    val files=tree.optJSONArray("tree")?:throw Exception("تعذر قراءة فهرس المصدر")
    var path:String?=null
    for(i in 0 until files.length()){val f=files.optJSONObject(i)?:continue;val p=f.optString("path");if(f.optString("type")=="blob"&&p.startsWith("data/")&&p.endsWith(".csv",true)){path=p;break}}
    val csvPath=path?:throw Exception("لم يتم العثور على CSV")
    val encoded=csvPath.split("/").joinToString("/"){URLEncoder.encode(it,"UTF-8").replace("+","%20")}
    val fresh=get("https://raw.githubusercontent.com/SweetEyesBirb/Meteorite-Landings-Map/main/$encoded")
    if(fresh.length<1000)throw Exception("ملف البيانات المستلم غير مكتمل")
    val temp=File(filesDir,"meteorites.csv.tmp");temp.writeText(fresh,Charsets.UTF_8);if(!temp.renameTo(cache)){cache.writeText(fresh,Charsets.UTF_8);temp.delete()}
   }
   val parsed=parseCsv(cache.readText(Charsets.UTF_8));if(parsed.isEmpty())throw Exception("لم تُستخرج سجلات بإحداثيات صالحة")
   localDb.replaceAll(parsed)
   runOnUiThread{data=parsed;applyFilter();status.text="تم تحميل ${data.size} سجلاً بإحداثيات وحفظها في قاعدة محلية. المصدر الحالي ليس قاعدة MetBull الرسمية الحية."}
  }catch(e:Exception){runOnUiThread{status.text="تعذر التحديث: ${e.message}. ${if(cache.exists())"سيُحتفظ بالملف المحلي إن أمكن." else "تحقق من الإنترنت."}";if(cache.exists())try{val parsed=parseCsv(cache.readText(Charsets.UTF_8));if(parsed.isNotEmpty()){data=parsed;localDb.replaceAll(parsed);applyFilter()}}catch(_:Exception){}}}}
 }
 private fun refreshFireballs(){
  status.text="جارٍ جلب أحدث أحداث الكرات النارية من NASA/JPL…"
  worker.execute {
   try {
    val raw=get("https://ssd-api.jpl.nasa.gov/fireball.api?req-loc=true&limit=100&sort=-date")
    val parsed=parseFireballs(raw)
    if(parsed.isEmpty()) throw Exception("لم تُرجع الخدمة أحداثاً ذات إحداثيات")
    val temp=File(filesDir,"nasa_fireballs_cache.json.tmp")
    temp.writeText(raw,Charsets.UTF_8)
    if(!temp.renameTo(fireballCache)){fireballCache.writeText(raw,Charsets.UTF_8);temp.delete()}
    runOnUiThread {
     fireballEvents=parsed
     drawFireballs()
     val newest=parsed.firstOrNull()?.dateUtc?:"غير معروف"
     status.text="رادار الأحداث: ${parsed.size} حدثاً من NASA/JPL. أحدث وقت مسجل: $newest UTC. البيانات ليست بثاً لحظياً."
     showFireballList()
    }
   }catch(e:Exception){
    val cached=try{if(fireballCache.exists())parseFireballs(fireballCache.readText(Charsets.UTF_8))else emptyList()}catch(_:Exception){emptyList()}
    runOnUiThread {
     if(cached.isNotEmpty()){
      fireballEvents=cached;drawFireballs()
      status.text="تعذر الاتصال بـ NASA/JPL؛ عُرضت آخر بيانات محفوظة (${cached.size} حدث). ${e.message}"
      showFireballList()
     }else status.text="تعذر تحديث الرادار: ${e.message}. تحقق من الإنترنت وحاول لاحقاً."
    }
   }
  }
 }

 private fun parseFireballs(raw:String):List<FireballEvent>{
  val root=JSONObject(raw)
  val fields=root.optJSONArray("fields")?:return emptyList()
  val rows=root.optJSONArray("data")?:return emptyList()
  val indexes=(0 until fields.length()).associateBy{fields.optString(it)}
  fun value(row:org.json.JSONArray,key:String):String?{
   val index=indexes[key]?:return null
   if(index>=row.length()||row.isNull(index))return null
   return row.optString(index).takeIf{it.isNotBlank()&&it!="null"}
  }
  fun number(row:org.json.JSONArray,key:String)=value(row,key)?.toDoubleOrNull()
  val result=ArrayList<FireballEvent>(rows.length())
  for(i in 0 until rows.length()){
   val row=rows.optJSONArray(i)?:continue
   val date=value(row,"date")?:continue
   val lat=number(row,"lat")?:continue
   val lon=number(row,"lon")?:continue
   val latDir=value(row,"lat-dir")
   val lonDir=value(row,"lon-dir")
   val signedLat=if(latDir.equals("S",true)) -kotlin.math.abs(lat) else kotlin.math.abs(lat)
   val signedLon=if(lonDir.equals("W",true)) -kotlin.math.abs(lon) else kotlin.math.abs(lon)
   if(signedLat !in -90.0..90.0||signedLon !in -180.0..180.0)continue
   result.add(FireballEvent(date,signedLat,signedLon,number(row,"alt"),number(row,"energy"),number(row,"impact-e")))
  }
  return result.distinctBy{Triple(it.dateUtc,it.latitude,it.longitude)}
 }

 private fun drawFireballs(){
  fireballMarkers.forEach{map.overlays.remove(it)}
  fireballMarkers.clear()
  for(event in fireballEvents){
   val marker=Marker(map)
   marker.position=GeoPoint(event.latitude,event.longitude)
   marker.title="حدث كرة نارية — NASA/JPL"
   marker.snippet=fireballDescription(event)
   marker.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM)
   marker.setOnMarkerClickListener{_,_->showFireball(event);true}
   map.overlays.add(marker)
   fireballMarkers.add(marker)
  }
  map.invalidate()
 }

 private fun fireballDescription(event:FireballEvent):String{
  val altitude=event.altitudeKm?.let{"${it} km"}?:"غير متاح"
  val radiated=event.radiatedEnergy?.let{"${it} × 10¹⁰ J"}?:"غير متاحة"
  val impact=event.impactEnergyKt?.let{"${it} kt"}?:"غير متاحة"
  return "وقت ذروة السطوع: ${event.dateUtc} UTC\nالإحداثيات: ${event.latitude}, ${event.longitude}\nالارتفاع عند الذروة: $altitude\nالطاقة المشعة التقديرية: $radiated\nطاقة الاصطدام التقديرية: $impact\nالمصدر: NASA/JPL CNEOS"
 }

 private fun showFireball(event:FireballEvent){
  AlertDialog.Builder(this)
   .setTitle("حدث كرة نارية — NASA/JPL")
   .setMessage(fireballDescription(event)+"\n\nهذه نقطة ذروة السطوع المسجلة، وليست بالضرورة نقطة اصطدام أو موقع سقوط نيزك على الأرض.")
   .setPositiveButton("الانتقال إلى الموقع"){_,_->map.controller.animateTo(GeoPoint(event.latitude,event.longitude));map.controller.setZoom(7.0)}
   .setNegativeButton("إغلاق",null).show()
 }

 private fun showFireballList(){
  if(fireballEvents.isEmpty())return
  val rows=fireballEvents.take(100).map{event->
   "${event.dateUtc} UTC — ${String.format(Locale.US,"%.2f",event.latitude)}, ${String.format(Locale.US,"%.2f",event.longitude)}"
  }.toTypedArray()
  AlertDialog.Builder(this)
   .setTitle("أحداث الكرات النارية (${fireballEvents.size})")
   .setItems(rows){_,index->showFireball(fireballEvents[index])}
   .setNeutralButton("تحديث الآن"){_,_->refreshFireballs()}
   .setNegativeButton("إغلاق",null).show()
 }

 private fun get(url:String):String{val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=20000;c.readTimeout=90000;c.setRequestProperty("User-Agent","MeteoriteAtlas-Android/1.1");try{if(c.responseCode !in 200..299)throw Exception("HTTP ${c.responseCode}");return c.inputStream.bufferedReader(Charsets.UTF_8).use{it.readText()}}finally{c.disconnect()}}
 private fun parseCsv(s:String):List<Meteorite>{
  if(s.isBlank())return emptyList()
  fun parse(delimiter:Char):List<List<String>>{
   val rows=mutableListOf<List<String>>();var row=mutableListOf<String>();val cell=StringBuilder();var quoted=false;var i=0
   while(i<s.length){val ch=s[i];if(quoted){if(ch=='"'&&i+1<s.length&&s[i+1]=='"'){cell.append('"');i++}else if(ch=='"')quoted=false else cell.append(ch)}else when(ch){'"'->quoted=true;delimiter->{row.add(cell.toString());cell.setLength(0)};'\n'->{row.add(cell.toString().trimEnd('\r'));rows.add(row);row=mutableListOf();cell.setLength(0)};else->cell.append(ch)};i++}
   if(cell.isNotEmpty()||row.isNotEmpty()){row.add(cell.toString().trimEnd('\r'));rows.add(row)}
   return rows
  }
  val first=s.lineSequence().firstOrNull().orEmpty()
  val delimiter=listOf(',', ';', '\t').maxByOrNull{d->first.count{it==d}}?:','
  val rows=parse(delimiter);if(rows.size<2)return emptyList()
  fun norm(x:String)=x.trim().removePrefix("\uFEFF").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"),"")
  val h=rows[0].map(::norm)
  fun v(r:List<String>,vararg keys:String):String{for(k in keys){val n=h.indexOf(norm(k));if(n>=0&&n<r.size&&r[n].isNotBlank())return r[n].trim()};return ""}
  val out=ArrayList<Meteorite>()
  for(r in rows.drop(1)){
   val lat=v(r,"reclat","latitude","lat","find latitude","coordinates latitude").replace(',','.').toDoubleOrNull()?:continue
   val lon=v(r,"reclong","longitude","lon","lng","long","find longitude","coordinates longitude").replace(',','.').toDoubleOrNull()?:continue
   if(lat !in -90.0..90.0||lon !in -180.0..180.0||(lat==0.0&&lon==0.0))continue
   val name=v(r,"name","meteorite","meteorite name","official name")
   if(name.isBlank())continue
   val placeV=v(r,"place","location","find location","country");val yearV=v(r,"year","date","fall year","find year")
   if(!AlgeriaFilter.keep(lat,lon,placeV,yearV))continue
   out.add(Meteorite(name,placeV,v(r,"recclass","classification","class","type"),yearV,v(r,"mass (g)","mass","mass_g","mass g"),v(r,"fall","fell_found","status"),lat,lon))
  }
  return out.distinctBy{Triple(it.name.lowercase(Locale.ROOT),it.lat,it.lon)}
 }
 private fun applyFilter(){val q=query.text.toString().trim().lowercase(Locale.ROOT);val mode=fallFilter.selectedItemPosition;shown=data.filter{m->val match=q.isEmpty()||listOf(m.name,m.place,m.cls,m.year).any{it.lowercase(Locale.ROOT).contains(q)};val fell=m.fall.lowercase(Locale.ROOT).let{it=="fell"||it=="fall"||it.startsWith("y")||it.contains("fell")};match&&(mode==0||(mode==1&&fell)||(mode==2&&!fell))};lastZoom=-1;draw();count.text="قاعدة البيانات: ${data.size} سجل بإحداثيات | النتائج: ${shown.size}";if(q.isNotEmpty()&&shown.size==1){map.controller.animateTo(GeoPoint(shown[0].lat,shown[0].lon));map.controller.setZoom(9.0)}}
 private fun draw(){mapMarkers.forEach{map.overlays.remove(it)};mapMarkers.clear();val z=map.zoomLevelDouble;val bb=try{map.boundingBox}catch(_:Exception){null};val src=if(bb==null||z<3.0)shown else{val pl=(bb.latNorth-bb.latSouth)*0.3;val po=(bb.lonEast-bb.lonWest)*0.3;shown.filter{it.lat in (bb.latSouth-pl)..(bb.latNorth+pl)&&it.lon in (bb.lonWest-po)..(bb.lonEast+po)}};var cell=when{z<3->18.0;z<4.5->8.0;z<6->3.0;z<8->0.8;z<10->0.15;else->0.0};if(cell==0.0&&src.size>1200)cell=0.02;val groups=if(cell==0.0)src.map{listOf(it)}else src.groupBy{Pair(floor(it.lat/cell).toInt(),floor(it.lon/cell).toInt())}.values
  for(g in groups){val m=Marker(map);m.position=GeoPoint(g.map{it.lat}.average(),g.map{it.lon}.average());m.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM);if(g.size==1){val a=g[0];m.title=a.name;m.snippet="الموقع: ${a.place}\nالتصنيف: ${a.cls}\nالسنة: ${a.year}\nالكتلة: ${a.mass} غرام\nالحالة: ${a.fall}\nالإحداثيات: ${a.lat}, ${a.lon}"}else{m.title="${g.size} نيزك في هذه المنطقة";m.snippet=g.take(20).joinToString("\n"){it.name} + (if(g.size>20) "\n…" else "")};m.setOnMarkerClickListener { marker, _ -> if(g.size==1) showMeteorite(g[0]) else showGroup(g); true };map.overlays.add(m);mapMarkers.add(m)}
  status.text="المواقع المطابقة: ${shown.size}. كبّر الخريطة لرؤية النقاط الفردية.";map.invalidate()
 }
 private fun showMeteorite(m:Meteorite){
  AlertDialog.Builder(this).setTitle(m.name.ifBlank{"نيزك بلا اسم"})
   .setMessage("الموقع: ${m.place.ifBlank{"غير محدد"}}\nالتصنيف: ${m.cls.ifBlank{"غير متاح"}}\nسنة السقوط/العثور: ${m.year.ifBlank{"غير معروفة"}}\nالكتلة: ${m.mass.ifBlank{"غير متاحة"}} غرام\nالحالة: ${m.fall.ifBlank{"غير محددة"}}\nخط العرض: ${m.lat}\nخط الطول: ${m.lon}\n\nالمصدر: مجموعة بيانات عامة؛ تحقق من السجل الرسمي قبل الاستشهاد العلمي.")
   .setPositiveButton("إغلاق",null).show()
 }
 private fun showGroup(group:List<Meteorite>){
  val names=group.take(100).map{it.name}.toTypedArray()
  AlertDialog.Builder(this).setTitle("${group.size} سجل في هذه المنطقة")
   .setItems(names){_,which->showMeteorite(group[which])}.setNegativeButton("إغلاق",null).show()
 }
 private fun showStats(){
  val withYear=data.mapNotNull{it.year.take(4).toIntOrNull()}
  val latest=withYear.maxOrNull()?.toString()?:"غير معروف"
  val fell=data.count{it.fall.equals("Fell",true)}
  val found=data.size-fell
  val classes=data.map{it.cls}.filter{it.isNotBlank()}.distinct().size
  val msg="إجمالي السجلات ذات الإحداثيات: ${data.size}\nالسقوط المشاهد (Fell): $fell\nالعثور عليه (Found/غير ذلك): $found\nتصنيفات مختلفة: $classes\nأحدث سنة موجودة في الملف: $latest\n\nتنبيه: لا يعني ذلك أن قاعدة الأسماء الرسمية كاملة؛ السجلات بلا إحداثيات لا تظهر على الخريطة."
  AlertDialog.Builder(this).setTitle("إحصائيات الأطلس").setMessage(msg).setPositiveButton("حسناً",null).show()
 }
 @Deprecated("Deprecated in Android API; retained for broad device compatibility")
 override fun onActivityResult(requestCode:Int,resultCode:Int,result:Intent?){
  super.onActivityResult(requestCode,resultCode,result);if(resultCode!=RESULT_OK)return;val uri=result?.data?:return
  if(requestCode==4102){status.text="جارٍ فحص واستيراد CSV…";worker.execute{try{
   val raw=contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}?:throw Exception("تعذر فتح الملف")
   val parsed=parseCsv(raw);if(parsed.isEmpty())throw Exception("لم أجد سجلات بإحداثيات. الملف قد لا يحتوي أعمدة latitude/longitude أو reclat/reclong؛ لم أستبدل القاعدة الحالية.")
   val tmp=File(filesDir,"metbull_official.csv.tmp");tmp.writeText(raw,Charsets.UTF_8);if(!tmp.renameTo(officialCache)){officialCache.writeText(raw,Charsets.UTF_8);tmp.delete()}
   localDb.replaceAll(parsed);runOnUiThread{data=parsed;applyFilter();status.text="نجح الفحص والاستيراد: ${parsed.size} سجل بإحداثيات. أحدث سنة: ${parsed.mapNotNull{it.year.take(4).toIntOrNull()}.maxOrNull()?:"غير معروفة"}. السجلات بلا إحداثيات لم تظهر على الخريطة."}
  }catch(e:Exception){runOnUiThread{status.text="فشل الاستيراد دون حذف البيانات السابقة: ${e.message}"}}}}

 }
 override fun onResume(){super.onResume();if(::map.isInitialized)map.onResume();myLoc?.enableMyLocation()}
 override fun onPause(){myLoc?.disableMyLocation();if(::map.isInitialized)map.onPause();super.onPause()}
 override fun onDestroy(){worker.shutdownNow();if(::map.isInitialized)map.onDetach();super.onDestroy()}
}


private class MeteoriteDb(context:Context):SQLiteOpenHelper(context,"meteorite_atlas.db",null,1){
 override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE meteorites (name TEXT, place TEXT, cls TEXT, year TEXT, mass TEXT, fall TEXT, lat REAL, lon REAL, PRIMARY KEY(name,lat,lon))")}
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
 fun replaceAll(items:List<Meteorite>){val db=writableDatabase;db.beginTransaction();try{db.delete("meteorites",null,null);val stmt=db.compileStatement("INSERT OR REPLACE INTO meteorites(name,place,cls,year,mass,fall,lat,lon) VALUES(?,?,?,?,?,?,?,?)");for(m in items){stmt.clearBindings();stmt.bindString(1,m.name);stmt.bindString(2,m.place);stmt.bindString(3,m.cls);stmt.bindString(4,m.year);stmt.bindString(5,m.mass);stmt.bindString(6,m.fall);stmt.bindDouble(7,m.lat);stmt.bindDouble(8,m.lon);stmt.executeInsert()};stmt.close();db.setTransactionSuccessful()}finally{db.endTransaction()}}
}
