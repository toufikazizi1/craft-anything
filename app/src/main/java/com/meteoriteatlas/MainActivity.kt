package com.meteoriteatlas

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
 private lateinit var points:PointsOverlay
 private lateinit var query:EditText
 private lateinit var countChip:TextView
 private lateinit var listInfo:TextView
 private lateinit var fallFilter:Spinner
 private lateinit var mapStyle:Spinner
 private lateinit var offlineButton:Button
 private lateinit var alertsBtn:Button
 private lateinit var metAdapter:ArrayAdapter<String>
 private lateinit var fireAdapter:ArrayAdapter<String>
 private lateinit var fireInfo:TextView
 private val worker=Executors.newSingleThreadExecutor()
 private val cache by lazy { File(filesDir,"meteorites.csv") }
 private val officialCache by lazy { File(filesDir,"metbull_official.csv") }
 private val localDb by lazy { MeteoriteDb(this) }
 private val prefs by lazy { getSharedPreferences("atlas",Context.MODE_PRIVATE) }
 private var data=emptyList<Meteorite>()
 private var shown=emptyList<Meteorite>()
 private var listed=emptyList<Meteorite>()
 private var fireballEvents=emptyList<FireballEvent>()
 private val fireballCache by lazy { File(filesDir,"nasa_fireballs_cache.json") }
 private var offline=false
 private val styles=listOf("هجين: أقمار صناعية + أسماء الولايات والطرق (موصى به)","خريطة الطرق (OpenStreetMap)","أقمار صناعية فقط","تضاريس")
 private val labelOverlays=ArrayList<TilesOverlay>()
 private var myLoc:MyLocationNewOverlay?=null
 private val pages=ArrayList<View>()
 private val tabs=ArrayList<TextView>()
 private var styleReady=false

 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val mapRoot=File(filesDir,"osmdroid");mapRoot.mkdirs()
  Configuration.getInstance().setOsmdroidBasePath(mapRoot)
  Configuration.getInstance().setOsmdroidTileCache(File(mapRoot,"tiles"))
  Configuration.getInstance().userAgentValue="$packageName/1.3 (Meteorite Atlas Android)"
  Configuration.getInstance().tileFileSystemCacheMaxBytes=512L*1024*1024
  Configuration.getInstance().tileFileSystemCacheTrimBytes=384L*1024*1024
  offline=prefs.getBoolean("offline",false)
  makeUi()
  points=PointsOverlay(this,{m->showMeteorite(m)},{g,c->onGroupTap(g,c)},{e->showFireball(e)})
  map.overlays.add(points)
  setMapStyle(prefs.getInt("style2",0).coerceIn(0,3))
  styleReady=true
  map.controller.setZoom(4.8);map.controller.setCenter(GeoPoint(28.0,2.5))
  try{if(fireballCache.exists()){fireballEvents=parseFireballs(fireballCache.readText(Charsets.UTF_8));points.fires=fireballEvents;refreshFireList()}}catch(_:Exception){}
  showPage(0)
  load(false)
 }

 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 private fun say(t:String){Toast.makeText(this,t,Toast.LENGTH_LONG).show()}
 private fun btn(t:String,f:()->Unit)=Button(this).apply{text=t;setOnClickListener{f()}}
 private fun lp(h:Int)=LinearLayout.LayoutParams(-1,h).apply{setMargins(0,dp(4),0,dp(4))}
 private fun title(t:String)=TextView(this).apply{text=t;textSize=21f;setTextColor(Color.WHITE);setPadding(dp(2),dp(4),dp(2),dp(10))}
 private fun note(t:String)=TextView(this).apply{text=t;textSize=13f;setTextColor(Color.rgb(190,205,212));setPadding(dp(2),dp(4),dp(2),dp(4))}
 private fun pageBox()=LinearLayout(this).apply{orientation=1;setBackgroundColor(Color.rgb(12,25,34));setPadding(dp(12),dp(10),dp(12),dp(6))}
 private fun darkAdapter():ArrayAdapter<String> = object:ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,ArrayList<String>()){
  override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{val v=super.getView(position,convertView,parent) as TextView;v.setTextColor(Color.WHITE);v.textSize=14f;return v}
 }
 private fun round(color:Int)=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(color);setStroke(dp(1),Color.WHITE)}

 private fun makeUi(){
  val root=LinearLayout(this).apply{orientation=1;setBackgroundColor(Color.rgb(12,25,34))}
  val content=FrameLayout(this)
  root.addView(content,LinearLayout.LayoutParams(-1,0,1f))

  // ---- Page 0: full-screen map only ----
  val mapPage=FrameLayout(this)
  map=MapView(this).apply{setMultiTouchControls(true);setBuiltInZoomControls(true);isTilesScaledToDpi=true;setUseDataConnection(!offline);minZoomLevel=3.0}
  mapPage.addView(map,FrameLayout.LayoutParams(-1,-1))
  countChip=TextView(this).apply{text="☄ …";textSize=13f;setTextColor(Color.WHITE);setPadding(dp(12),dp(6),dp(12),dp(6));background=GradientDrawable().apply{cornerRadius=dp(16).toFloat();setColor(Color.argb(220,16,35,45))}}
  mapPage.addView(countChip,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply{setMargins(dp(12),dp(12),0,0)})
  fun fab(t:String,k:Int,f:()->Unit){
   val v=TextView(this).apply{text=t;textSize=22f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);background=round(Color.argb(235,16,35,45));setOnClickListener{f()}}
   mapPage.addView(v,FrameLayout.LayoutParams(dp(52),dp(52),Gravity.BOTTOM or Gravity.END).apply{setMargins(0,0,dp(14),dp(90+k*62))})
  }
  fab("📍",0){locate()}
  fab("🇩🇿",1){map.controller.setZoom(4.8);map.controller.animateTo(GeoPoint(28.0,2.5))}
  content.addView(mapPage,FrameLayout.LayoutParams(-1,-1));pages.add(mapPage)

  // ---- Page 1: meteorite list / search ----
  val p1=pageBox()
  p1.addView(title("☄ النيازك الجزائرية"))
  query=EditText(this).apply{hint="ابحث بالاسم أو المكان أو التصنيف أو السنة";setSingleLine(true);textSize=14f;setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY);imeOptions=EditorInfo.IME_ACTION_SEARCH;setPadding(dp(12),0,dp(12),0);setBackgroundColor(Color.rgb(31,52,63))}
  p1.addView(query,lp(dp(46)))
  fallFilter=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("كل الحالات","سقط وشوهد","عُثر عليه"));setBackgroundColor(Color.rgb(220,230,235))}
  p1.addView(fallFilter,lp(dp(42)))
  val row=LinearLayout(this).apply{orientation=0}
  row.addView(btn("بحث"){applyFilter()},LinearLayout.LayoutParams(0,dp(46),1f))
  row.addView(btn("إحصائيات"){showStats()},LinearLayout.LayoutParams(0,dp(46),1f))
  row.addView(btn("بلا موقع"){showNoLocation()},LinearLayout.LayoutParams(0,dp(46),1f))
  p1.addView(row)
  listInfo=note("…");p1.addView(listInfo)
  metAdapter=darkAdapter()
  val metList=ListView(this).apply{adapter=metAdapter;setOnItemClickListener{_,_,i,_->listed.getOrNull(i)?.let{showMeteorite(it)}}}
  p1.addView(metList,LinearLayout.LayoutParams(-1,0,1f))
  content.addView(p1,FrameLayout.LayoutParams(-1,-1));pages.add(p1)

  // ---- Page 2: radar + alerts ----
  val p2=pageBox()
  p2.addView(title("📡 رادار الكرات النارية (NASA)"))
  alertsBtn=btn(""){toggleAlerts()};p2.addView(alertsBtn,lp(dp(48)));updateAlertsButton()
  p2.addView(btn("تحديث الأحداث من NASA/JPL"){refreshFireballs()},lp(dp(48)))
  fireInfo=note("اضغط تحديث لجلب أحدث الأحداث. البيانات من NASA/JPL وليست بثاً لحظياً؛ النقطة هي ذروة سطوع الكرة النارية وليست بالضرورة موقع سقوط.");p2.addView(fireInfo)
  fireAdapter=darkAdapter()
  val fireList=ListView(this).apply{adapter=fireAdapter;setOnItemClickListener{_,_,i,_->fireballEvents.getOrNull(i)?.let{showFireball(it)}}}
  p2.addView(fireList,LinearLayout.LayoutParams(-1,0,1f))
  content.addView(p2,FrameLayout.LayoutParams(-1,-1));pages.add(p2)

  // ---- Page 3: settings ----
  val sv=ScrollView(this).apply{setBackgroundColor(Color.rgb(12,25,34))}
  val p3=pageBox()
  p3.addView(title("⚙ الإعدادات"))
  p3.addView(note("نمط الخريطة"))
  mapStyle=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,styles);setBackgroundColor(Color.rgb(220,230,235))}
  p3.addView(mapStyle,lp(dp(46)))
  offlineButton=btn(""){offline=!offline;prefs.edit().putBoolean("offline",offline).apply();map.setUseDataConnection(!offline);updateOfflineButton();say(if(offline)"وضع عدم الاتصال: يُعرض فقط ما سبق تخزينه من الخريطة، ونقاط النيازك محلية." else "الاتصال مفعّل: تُحمّل مربعات الخريطة عند عرضها.")}
  p3.addView(offlineButton,lp(dp(48)));updateOfflineButton()
  p3.addView(btn("🔄 تحديث بيانات النيازك"){load(true)},lp(dp(48)))
  p3.addView(btn("📥 استيراد CSV من Meteoritical Bulletin"){val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="text/*"};startActivityForResult(i,4102)},lp(dp(48)))
  p3.addView(note("• النقاط تعمل بدون إنترنت (محفوظة في التطبيق).\n• الخريطة تُخزَّن مؤقتاً أثناء تصفحها؛ تصفّح المنطقة وأنت متصل لتبقى متاحة بعدها.\n• GPS يعمل بدون إنترنت.\n• التنبيهات والرادار يحتاجان إنترنت.\n\nالمصادر: Meteoritical Bulletin • NASA/JPL CNEOS\nالخرائط: © OpenStreetMap contributors • © Esri • OpenTopoMap"))
  sv.addView(p3,FrameLayout.LayoutParams(-1,-2))
  content.addView(sv,FrameLayout.LayoutParams(-1,-1));pages.add(sv)

  // ---- bottom navigation ----
  val nav=LinearLayout(this).apply{orientation=0;setBackgroundColor(Color.rgb(16,35,45))}
  val names=listOf("🗺\nالخريطة","☄\nالنيازك","📡\nالرادار","⚙\nالإعدادات")
  names.forEachIndexed{i,n->
   val t=TextView(this).apply{text=n;textSize=12f;gravity=Gravity.CENTER;setTextColor(Color.LTGRAY);setOnClickListener{showPage(i)}}
   tabs.add(t);nav.addView(t,LinearLayout.LayoutParams(0,dp(58),1f))
  }
  root.addView(nav,LinearLayout.LayoutParams(-1,-2))
  setContentView(root)

  query.setOnEditorActionListener{_,a,_->if(a==EditorInfo.IME_ACTION_SEARCH){applyFilter();true}else false}
  fallFilter.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){if(data.isNotEmpty())applyFilter()}}
  mapStyle.setSelection(prefs.getInt("style2",0).coerceIn(0,3))
  mapStyle.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){if(styleReady)setMapStyle(pos)}}
 }

 private fun showPage(i:Int){
  pages.forEachIndexed{k,v->v.visibility=if(k==i)View.VISIBLE else View.GONE}
  tabs.forEachIndexed{k,t->t.setTextColor(if(k==i)Color.rgb(77,208,225) else Color.LTGRAY)}
  if(i!=1){val im=getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager;im.hideSoftInputFromWindow(query.windowToken,0)}
 }
 private fun goTo(lat:Double,lon:Double,zoom:Double){showPage(0);map.controller.setZoom(zoom);map.controller.animateTo(GeoPoint(lat,lon))}
 private fun onGroupTap(g:List<Meteorite>,center:GeoPoint){
  if(map.zoomLevelDouble>=16.5)showGroup(g) else {map.controller.setZoom(minOf(map.zoomLevelDouble+2.5,18.0));map.controller.animateTo(center)}
 }

 private fun locate(){
  if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),5001);return}
  if(myLoc==null){val ov=MyLocationNewOverlay(GpsMyLocationProvider(this),map);myLoc=ov;map.overlays.add(ov)}
  val o=myLoc?:return
  o.enableMyLocation();o.enableFollowLocation();say("جارٍ تحديد موقعك بالـGPS… تأكد أن الموقع مفعّل في الهاتف.")
  o.runOnFirstFix{runOnUiThread{val p=o.myLocation;if(p!=null){map.controller.setZoom(13.0);map.controller.animateTo(p);say("تم تحديد موقعك.")}}}
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==5002){if(grantResults.any{it==PackageManager.PERMISSION_GRANTED})startAlerts() else say("صلاحية الإشعارات مرفوضة، فلن تصلك التنبيهات.")}
  if(requestCode==5001){if(grantResults.any{it==PackageManager.PERMISSION_GRANTED})locate() else say("صلاحية الموقع مرفوضة. فعّلها من إعدادات التطبيق.")}
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
  }catch(e:Exception){runOnUiThread{say("تعذر فتح القائمة: ${e.message}")}}}
 }
 private fun updateAlertsButton(){alertsBtn.text=if(prefs.getBoolean("alerts",false))"🔔 تنبيهات الجزائر: مفعّلة (اضغط للإيقاف)" else "🔕 تنبيهات الجزائر: متوقفة (اضغط للتفعيل)"}
 private fun toggleAlerts(){
  if(prefs.getBoolean("alerts",false)){WorkManager.getInstance(this).cancelUniqueWork("dz_alerts");prefs.edit().putBoolean("alerts",false).apply();updateAlertsButton();say("تم إيقاف التنبيهات.");return}
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
  say("التنبيهات مفعّلة: يفحص التطبيق NASA كل 15 دقيقة تقريباً (يحتاج إنترنت).")
 }
 private fun updateOfflineButton(){offlineButton.text=if(offline)"● وضع عدم الاتصال: مفعّل (اضغط للاتصال)" else "● وضع الاتصال: مفعّل (اضغط لعدم الاتصال)"}

 private fun esri(name:String,path:String,ext:String)=object:OnlineTileSourceBase(name,0,19,256,ext,arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$path/MapServer/tile/")){
  override fun getTileURLString(tile:Long):String{val z=MapTileIndex.getZoom(tile);val x=MapTileIndex.getX(tile);val y=MapTileIndex.getY(tile);return "https://server.arcgisonline.com/ArcGIS/rest/services/$path/MapServer/tile/$z/$y/$x"}
 }
 private fun labelLayer(src:OnlineTileSourceBase):TilesOverlay{
  val ov=TilesOverlay(MapTileProviderBasic(this,src),this)
  ov.setLoadingBackgroundColor(Color.TRANSPARENT);ov.setLoadingLineColor(Color.TRANSPARENT)
  return ov
 }
 private fun setMapStyle(i:Int){
  if(!::map.isInitialized)return
  labelOverlays.forEach{map.overlays.remove(it)};labelOverlays.clear()
  val imagery=esri("Esri World Imagery","World_Imagery",".jpg")
  when(i){
   0->{
    map.setTileSource(imagery)
    val places=labelLayer(esri("Esri Boundaries Places","Reference/World_Boundaries_and_Places",".png"))
    val roads=labelLayer(esri("Esri Transportation","Reference/World_Transportation",".png"))
    map.overlays.add(0,roads);map.overlays.add(1,places)
    labelOverlays.add(roads);labelOverlays.add(places)
   }
   2->map.setTileSource(imagery)
   3->map.setTileSource(XYTileSource("OpenTopoMap",0,17,256,".png",arrayOf("https://a.tile.opentopomap.org/","https://b.tile.opentopomap.org/","https://c.tile.opentopomap.org/")))
   else->map.setTileSource(TileSourceFactory.MAPNIK)
  }
  prefs.edit().putInt("style2",i).apply();map.invalidate()
 }

 private fun load(force:Boolean){
  if(officialCache.exists() && officialCache.length()>1000){try{val official=parseCsv(officialCache.readText(Charsets.UTF_8));if(official.isNotEmpty()){data=official;localDb.replaceAll(official);applyFilter();say("تم تحميل ${official.size} سجلاً من ملف MetBull الذي استوردته.");return}}catch(_:Exception){}}
  if(!cache.exists()||cache.length()<1000){try{assets.open("meteorites.csv").use{i->cache.outputStream().use{o->i.copyTo(o)}}}catch(_:Exception){}}
  if(force)say("جارٍ تحديث البيانات…")
  worker.execute{try{
   if(force){
    val tree=JSONObject(get("https://api.github.com/repos/SweetEyesBirb/Meteorite-Landings-Map/git/trees/main?recursive=1"))
    val files=tree.optJSONArray("tree")?:throw Exception("تعذر قراءة فهرس المصدر")
    var path:String?=null
    for(i in 0 until files.length()){val f=files.optJSONObject(i)?:continue;val p=f.optString("path");if(f.optString("type")=="blob"&&p.startsWith("data/")&&p.endsWith(".csv",true)){path=p;break}}
    val csvPath=path?:throw Exception("لم يتم العثور على CSV")
    val encoded=csvPath.split("/").joinToString("/"){URLEncoder.encode(it,"UTF-8").replace("+","%20")}
    val fresh=get("https://raw.githubusercontent.com/SweetEyesBirb/Meteorite-Landings-Map/main/$encoded")
    if(fresh.length<1000)throw Exception("ملف البيانات المستلم غير مكتمل")
    val freshParsed=parseCsv(fresh)
    if(freshParsed.isEmpty())throw Exception("المصدر لا يحتوي نيازك جزائرية 2000–2026")
    val temp=File(filesDir,"meteorites.csv.tmp");temp.writeText(fresh,Charsets.UTF_8);if(!temp.renameTo(cache)){cache.writeText(fresh,Charsets.UTF_8);temp.delete()}
   }
   val parsed=parseCsv(cache.readText(Charsets.UTF_8));if(parsed.isEmpty())throw Exception("لم تُستخرج سجلات بإحداثيات صالحة")
   localDb.replaceAll(parsed)
   runOnUiThread{data=parsed;applyFilter();if(force)say("تم: ${data.size} نيزكاً جزائرياً.")}
  }catch(e:Exception){runOnUiThread{say("تعذر التحديث: ${e.message}");if(cache.exists())try{val parsed=parseCsv(cache.readText(Charsets.UTF_8));if(parsed.isNotEmpty()){data=parsed;applyFilter()}}catch(_:Exception){}}}}
 }
 private fun refreshFireList(){
  fireAdapter.clear()
  fireAdapter.addAll(fireballEvents.take(100).map{e->"${e.dateUtc} UTC — ${String.format(Locale.US,"%.2f",e.latitude)}, ${String.format(Locale.US,"%.2f",e.longitude)}"})
  fireInfo.text="الأحداث: ${fireballEvents.size} (من NASA/JPL، ليست بثاً لحظياً). تظهر على الخريطة كنقاط صفراء. اضغط حدثاً للتفاصيل."
 }
 private fun refreshFireballs(){
  say("جارٍ جلب أحدث الأحداث من NASA/JPL…")
  worker.execute {
   try {
    val raw=get("https://ssd-api.jpl.nasa.gov/fireball.api?req-loc=true&limit=100&sort=-date")
    val parsed=parseFireballs(raw)
    if(parsed.isEmpty()) throw Exception("لم تُرجع الخدمة أحداثاً ذات إحداثيات")
    val temp=File(filesDir,"nasa_fireballs_cache.json.tmp")
    temp.writeText(raw,Charsets.UTF_8)
    if(!temp.renameTo(fireballCache)){fireballCache.writeText(raw,Charsets.UTF_8);temp.delete()}
    runOnUiThread {fireballEvents=parsed;points.fires=parsed;refreshFireList();map.invalidate();say("تم تحديث ${parsed.size} حدثاً.")}
   }catch(e:Exception){
    runOnUiThread {say("تعذر الاتصال بـ NASA/JPL: ${e.message}")}
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
   .setPositiveButton("عرض على الخريطة"){_,_->goTo(event.latitude,event.longitude,7.0)}
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

 private fun isFell(m:Meteorite):Boolean{val f=m.fall.lowercase(Locale.ROOT);return f=="fell"||f=="fall"||f.startsWith("y")||f.contains("fell")}
 private fun applyFilter(){
  val q=query.text.toString().trim().lowercase(Locale.ROOT);val mode=fallFilter.selectedItemPosition
  shown=data.filter{m->
   val match=q.isEmpty()||listOf(m.name,m.place,m.cls,m.year).any{it.lowercase(Locale.ROOT).contains(q)}
   val fell=isFell(m)
   match&&(mode==0||(mode==1&&fell)||(mode==2&&!fell))
  }
  points.mets=shown
  listed=shown.sortedByDescending{it.year}
  metAdapter.clear();metAdapter.addAll(listed.map{"${it.name}  •  ${it.year}  •  ${it.cls}"})
  val txt="☄ ${shown.size} نيزك جزائري (2000–2026) من أصل ${data.size}"
  countChip.text=txt;listInfo.text=txt+" — اضغط على أي نيزك لعرض معلوماته."
  map.invalidate()
 }
 private fun showMeteorite(m:Meteorite){
  val fell=if(isFell(m))"سقط وشوهد (Fell)" else "عُثر عليه (Found)"
  AlertDialog.Builder(this).setTitle("☄ "+m.name.ifBlank{"نيزك بلا اسم"})
   .setMessage("الاسم: ${m.name}\nالموقع: ${m.place.ifBlank{"غير محدد"}}\nالتصنيف: ${m.cls.ifBlank{"غير متاح"}}\nالسنة: ${m.year.ifBlank{"غير معروفة"}}\nالكتلة: ${m.mass.ifBlank{"غير متاحة"}} غرام\nالحالة: ${m.fall.ifBlank{fell}}\nخط العرض: ${m.lat}\nخط الطول: ${m.lon}\n\nالمصدر: Meteoritical Bulletin Database")
   .setPositiveButton("عرض على الخريطة"){_,_->goTo(m.lat,m.lon,12.0)}
   .setNegativeButton("إغلاق",null).show()
 }
 private fun showGroup(group:List<Meteorite>){
  val names=group.take(100).map{it.name}.toTypedArray()
  AlertDialog.Builder(this).setTitle("${group.size} سجل في هذه النقطة")
   .setItems(names){_,which->showMeteorite(group[which])}.setNegativeButton("إغلاق",null).show()
 }
 private fun showStats(){
  val withYear=data.mapNotNull{it.year.take(4).toIntOrNull()}
  val latest=withYear.maxOrNull()?.toString()?:"غير معروف"
  val fell=data.count{isFell(it)}
  val classes=data.map{it.cls}.filter{it.isNotBlank()}.distinct().size
  val msg="نيازك جزائرية بإحداثيات: ${data.size}\nسقوط مشاهد (Fell): $fell\nعُثر عليه (Found): ${data.size-fell}\nتصنيفات مختلفة: $classes\nأحدث سنة: $latest\n\nملاحظة: توجد نيازك جزائرية أخرى بلا إحداثيات في MetBull، تجدها في زر «بلا موقع»."
  AlertDialog.Builder(this).setTitle("إحصائيات الأطلس").setMessage(msg).setPositiveButton("حسناً",null).show()
 }
 @Deprecated("Deprecated in Android API; retained for broad device compatibility")
 override fun onActivityResult(requestCode:Int,resultCode:Int,result:Intent?){
  super.onActivityResult(requestCode,resultCode,result);if(resultCode!=RESULT_OK)return;val uri=result?.data?:return
  if(requestCode==4102){say("جارٍ فحص واستيراد CSV…");worker.execute{try{
   val raw=contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}?:throw Exception("تعذر فتح الملف")
   val parsed=parseCsv(raw);if(parsed.isEmpty())throw Exception("لم أجد نيازك جزائرية بإحداثيات (2000–2026) في الملف؛ لم تُستبدل القاعدة.")
   val tmp=File(filesDir,"metbull_official.csv.tmp");tmp.writeText(raw,Charsets.UTF_8);if(!tmp.renameTo(officialCache)){officialCache.writeText(raw,Charsets.UTF_8);tmp.delete()}
   localDb.replaceAll(parsed);runOnUiThread{data=parsed;applyFilter();say("تم استيراد ${parsed.size} سجل.")}
  }catch(e:Exception){runOnUiThread{say("فشل الاستيراد: ${e.message}")}}}}
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
