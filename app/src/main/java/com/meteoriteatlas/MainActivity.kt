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
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.content.ClipData
import android.content.ClipboardManager
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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.text.SimpleDateFormat
import java.util.Date
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.events.MapListener
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
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
 private lateinit var dlInfo:TextView
 private var downloading=false
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
 private val styles=listOf("هجين: أقمار Esri + أسماء الولايات والطرق (موصى به)","خريطة الطرق (OpenStreetMap)","أقمار Esri فقط","تضاريس + خطوط ارتفاع (OpenTopoMap)","أقمار Sentinel-2 مجانية + أسماء","أقمار Esri Clarity + أسماء (أوضح أحياناً)","ظلال التضاريس Esri + أسماء","خريطة MBTiles مستوردة (بدون إنترنت)")
 private val labelOverlays=ArrayList<TilesOverlay>()
 private var myLoc:MyLocationNewOverlay?=null
 private val pages=ArrayList<View>()
 private val tabs=ArrayList<TextView>()
 private var styleReady=false
 private lateinit var routeOv:RouteOverlay
 private lateinit var coordChip:TextView
 private lateinit var hud:TextView
 private lateinit var hudBox:LinearLayout
 private lateinit var rulerRow:LinearLayout
 private lateinit var navRow:LinearLayout
 private lateinit var places:PlacesOverlay
 private var ticks=0
 private lateinit var recFab:TextView
 private lateinit var navArrow:TextView
 private lateinit var nearBtn:Button
 private var pendingLoc:(()->Unit)?=null
 private var recSize=-1L
 private var recPts:List<TrackPt> = emptyList()
 private val shownTracks=HashSet<String>()
 private var heading=0f
 private var navBearing=0f
 private var usingMbtiles=false
 private val sm by lazy{getSystemService(Context.SENSOR_SERVICE) as SensorManager}
 private val sensorL=object:SensorEventListener{
  override fun onSensorChanged(e:SensorEvent){
   try{
    val r=FloatArray(9);SensorManager.getRotationMatrixFromVector(r,e.values);val o=FloatArray(3);SensorManager.getOrientation(r,o)
    val az=((Math.toDegrees(o[0].toDouble())+360.0)%360.0).toFloat()
    var diff=az-heading;while(diff>180f)diff-=360f;while(diff<-180f)diff+=360f
    heading=(heading+diff*0.25f+360f)%360f
    if(navTarget!=null)navArrow.rotation=((navBearing-heading)+360f)%360f
   }catch(_:Exception){}
  }
  override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
 }
 private val placesFile by lazy { File(filesDir,"places.tsv") }
 private lateinit var rulerFab:TextView
 private var compass:CompassOverlay?=null
 private var waypoints:MutableList<Waypoint> = ArrayList()
 private var navTarget:GeoPoint?=null
 private var navName=""
 private var rulerOn=false
 private val rulerPts=ArrayList<GeoPoint>()
 private val ui=Handler(Looper.getMainLooper())
 private val tick=object:Runnable{override fun run(){
  try{routeOv.myPos=myLoc?.myLocation;updateHud();if(navTarget!=null)map.invalidate()}catch(_:Exception){}
  try{
   if(prefs.getBoolean("rec_on",false)){val f=File(filesDir,"track_current.csv");if(f.exists()&&f.length()!=recSize){recSize=f.length();recPts=Gpx.readCsv(f);routeOv.recTrack=recPts.map{GeoPoint(it.lat,it.lon)};map.invalidate()}}
   if(ticks%2==0)updateCoord()
  }catch(_:Exception){}
  ticks++;if(ticks==2||ticks%400==0)refreshFireballs(true)
  ui.postDelayed(this,1500)
 }}

 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val mapRoot=File(filesDir,"osmdroid");mapRoot.mkdirs()
  Configuration.getInstance().setOsmdroidBasePath(mapRoot)
  Configuration.getInstance().setOsmdroidTileCache(File(mapRoot,"tiles"))
  Configuration.getInstance().userAgentValue="$packageName/1.3 (Meteorite Atlas Android)"
  Configuration.getInstance().tileFileSystemCacheMaxBytes=512L*1024*1024
  Configuration.getInstance().tileFileSystemCacheTrimBytes=384L*1024*1024
  Configuration.getInstance().tileFileSystemCacheMaxBytes=3000L*1024*1024
  Configuration.getInstance().tileFileSystemCacheTrimBytes=2500L*1024*1024
  Configuration.getInstance().setTileDownloadThreads(12.toShort())
  Configuration.getInstance().setTileFileSystemThreads(8.toShort())
  Configuration.getInstance().setTileDownloadMaxQueueSize(120.toShort())
  Configuration.getInstance().setTileFileSystemMaxQueueSize(120.toShort())
  Configuration.getInstance().setCacheMapTileCount(80.toShort())
  Configuration.getInstance().setExpirationOverrideDuration(1000L*60*60*24*365*5)
  try{File(filesDir,"meteorites.csv").delete()}catch(_:Exception){}
  offline=prefs.getBoolean("offline",false)
  makeUi()
  points=PointsOverlay(this,{m->showMeteorite(m)},{g,c->onGroupTap(g,c)},{e->showFireball(e)})
  places=PlacesOverlay(this){p->showPlace(p)}
  map.overlays.add(places)
  map.overlays.add(points)
  loadPlaces()
  waypoints=WpStore.load(prefs)
  routeOv=RouteOverlay(this,{w->showWaypoint(w)},{g->editWaypoint(null,g.latitude,g.longitude)},{g->rulerAdd(g)})
  routeOv.wps=ArrayList(waypoints)
  map.overlays.add(routeOv)
  try{val rg=RotationGestureOverlay(map);rg.isEnabled=true;map.overlays.add(rg)}catch(_:Exception){}
  try{
   val sb=ScaleBarOverlay(map);sb.setAlignBottom(true);sb.setScaleBarOffset(dp(12),dp(64));sb.setUnitsOfMeasure(ScaleBarOverlay.UnitsOfMeasure.metric);map.overlays.add(sb)
   val cp=CompassOverlay(this,InternalCompassOrientationProvider(this),map);cp.setCompassCenter(34f,150f);cp.enableCompass();compass=cp;map.overlays.add(cp)
  }catch(_:Exception){}
  map.addMapListener(object:MapListener{
   override fun onScroll(event:ScrollEvent?):Boolean{updateCoord();updateHud();return false}
   override fun onZoom(event:ZoomEvent?):Boolean{updateCoord();updateHud();return false}
  })
  setMapStyle(prefs.getInt("style2",0).coerceIn(0,7))
  styleReady=true
  map.controller.setZoom(4.8);map.controller.setCenter(GeoPoint(28.0,2.5))
  try{if(fireballCache.exists()){fireballEvents=parseFireballs(fireballCache.readText(Charsets.UTF_8));points.fires=fireballEvents;refreshFireList()}}catch(_:Exception){}
  showPage(0)
  updateCoord()
  if(prefs.getBoolean("rec_on",false)){recFab.background=round(Color.rgb(198,40,40))}
  if(prefs.getBoolean("rec_on",false)||prefs.getBoolean("near_on",false))syncService()
  ui.postDelayed(tick,1500)
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
  map=MapView(this).apply{setMultiTouchControls(true);setBuiltInZoomControls(true);isTilesScaledToDpi=false;setTilesScaleFactor(1.6f);setUseDataConnection(!offline);minZoomLevel=3.0}
  mapPage.addView(map,FrameLayout.LayoutParams(-1,-1))
  countChip=TextView(this).apply{text="☄ جارٍ التحميل…";textSize=13f;setTextColor(Color.WHITE);setPadding(dp(12),dp(6),dp(12),dp(6));background=GradientDrawable().apply{cornerRadius=dp(16).toFloat();setColor(Color.argb(220,16,35,45))}}
  mapPage.addView(countChip,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply{setMargins(dp(12),dp(12),0,0)})
  fun fab(t:String,k:Int,f:()->Unit):TextView{
   val v=TextView(this).apply{text=t;textSize=22f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);background=round(Color.argb(235,16,35,45));setOnClickListener{f()}}
   mapPage.addView(v,FrameLayout.LayoutParams(dp(52),dp(52),Gravity.BOTTOM or Gravity.END).apply{setMargins(0,0,dp(14),dp(90+k*62))})
   return v
  }
  fab("📍",0){locate(true)}
  fab("🇩🇿",1){map.setMapOrientation(0f);map.controller.setZoom(4.8);map.controller.animateTo(GeoPoint(28.0,2.5))}
  fab("➕",2){addWaypointMenu()}
  rulerFab=fab("📏",3){toggleRuler()}
  recFab=fab("⏺",5){toggleRecording()}
  fab("⬆N",4){map.setMapOrientation(0f);say("الشمال إلى الأعلى. لتدوير الخريطة: لفّ إصبعين عليها.")}
  hud=TextView(this).apply{textSize=13f;setTextColor(Color.WHITE);setPadding(dp(6),dp(4),dp(6),dp(2))}
  fun chip(t:String,f:()->Unit)=TextView(this).apply{text=t;textSize=12f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(dp(6),dp(9),dp(6),dp(9));background=GradientDrawable().apply{cornerRadius=dp(10).toFloat();setColor(Color.argb(255,0,60,70));setStroke(dp(1),Color.WHITE)};setOnClickListener{f()}}
  fun cp()=LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(2),dp(4),dp(2),0)}
  rulerRow=LinearLayout(this).apply{orientation=0;visibility=View.GONE}
  rulerRow.addView(chip("📍 ثبّت"){rulerFix()},cp())
  rulerRow.addView(chip("▶ ابدأ هنا"){rulerRestart()},cp())
  rulerRow.addView(chip("↩ تراجع"){rulerUndo()},cp())
  rulerRow.addView(chip("✖ إنهاء ومسح"){toggleRuler()},cp())
  navRow=LinearLayout(this).apply{orientation=0;visibility=View.GONE}
  navArrow=TextView(this).apply{text="⬆";textSize=30f;setTextColor(Color.WHITE);gravity=Gravity.CENTER}
  navRow.addView(navArrow,LinearLayout.LayoutParams(dp(46),dp(46)))
  navRow.addView(chip("⛔ إيقاف التوجيه"){navTarget=null;routeOv.navTarget=null;updateHud();map.invalidate()},cp())
  hudBox=LinearLayout(this).apply{orientation=1;visibility=View.GONE;setPadding(dp(8),dp(4),dp(8),dp(8));background=GradientDrawable().apply{cornerRadius=dp(12).toFloat();setColor(Color.argb(238,0,96,110))}}
  hudBox.addView(hud);hudBox.addView(rulerRow);hudBox.addView(navRow)
  mapPage.addView(hudBox,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply{setMargins(dp(12),dp(56),dp(12),0)})
  coordChip=TextView(this).apply{textSize=11f;setTextColor(Color.WHITE);setPadding(dp(10),dp(5),dp(10),dp(5));background=GradientDrawable().apply{cornerRadius=dp(10).toFloat();setColor(Color.argb(225,16,35,45))};setOnClickListener{coordMenu()}}
  mapPage.addView(coordChip,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply{setMargins(dp(10),0,0,dp(10))})
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
  p1.addView(btn("📌 علاماتي"){showWaypointList()},lp(dp(46)))
  listInfo=note("…");p1.addView(listInfo)
  metAdapter=darkAdapter()
  val metList=ListView(this).apply{adapter=metAdapter;setOnItemClickListener{_,_,i,_->listed.getOrNull(i)?.let{showMeteorite(it)}}}
  p1.addView(metList,LinearLayout.LayoutParams(-1,0,1f))
  content.addView(p1,FrameLayout.LayoutParams(-1,-1));pages.add(p1)

  // ---- Page 2: radar + alerts ----
  val p2=pageBox()
  p2.addView(title("📡 رادار الكرات النارية (NASA)"))
  alertsBtn=btn(""){toggleAlerts()};p2.addView(alertsBtn,lp(dp(48)));updateAlertsButton()
  p2.addView(btn("تحديث الأحداث من NASA/JPL"){refreshFireballs(false)},lp(dp(48)))
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
  p3.addView(note("الخريطة بدون إنترنت: حمّل الخريطة وأنت متصل (أقمار صناعية + أسماء الولايات والطرق) ثم تعمل بعدها بدون إنترنت."))
  p3.addView(btn("⬇ تحميل خريطة الجزائر كاملة (حتى zoom 9)"){downloadArea(BoundingBox(37.3,12.2,18.9,-8.8),3,9,"الجزائر كاملة")},lp(dp(48)))
  p3.addView(btn("⬇ تحميل المنطقة الظاهرة على الخريطة بتفصيل أعلى"){
   val bb=map.boundingBox
   downloadArea(BoundingBox(bb.latNorth,bb.lonEast,bb.latSouth,bb.lonWest),map.zoomLevelDouble.toInt().coerceAtLeast(3),14,"المنطقة الظاهرة")
  },lp(dp(48)))
  p3.addView(btn("⬇ تحميل أسماء القرى والآبار والعيون (OSM)"){downloadPlaces()},lp(dp(48)))
  dlInfo=note("");p3.addView(dlInfo)
  p3.addView(btn("🔄 إعادة تحميل قاعدة النيازك المحلية"){load(true)},lp(dp(48)))
  p3.addView(btn("📥 استيراد CSV من Meteoritical Bulletin"){val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="text/*"};startActivityForResult(i,4102)},lp(dp(48)))
  p3.addView(note("— المسارات والعلامات —"))
  p3.addView(btn("🥾 مساراتي المحفوظة"){showTracks()},lp(dp(48)))
  p3.addView(btn("📤 تصدير العلامات والمسارات (GPX)"){exportGpx()},lp(dp(48)))
  p3.addView(btn("📥 استيراد ملف GPX"){importGpx()},lp(dp(48)))
  nearBtn=btn(""){toggleNear()};p3.addView(nearBtn,lp(dp(48)));updateNearBtn()
  p3.addView(note("مسافة تنبيه الاقتراب"))
  val radii=intArrayOf(100,300,500,1000,2000)
  val radiusSp=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("100 م","300 م","500 م","1 كم","2 كم"));setBackgroundColor(Color.rgb(220,230,235));setSelection(radii.indexOf(prefs.getInt("near_radius",300)).coerceAtLeast(0))}
  radiusSp.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){prefs.edit().putInt("near_radius",radii[pos]).apply()}}
  p3.addView(radiusSp,lp(dp(46)))
  p3.addView(btn("🗺 استيراد خريطة MBTiles (بدون إنترنت)"){importMbtiles()},lp(dp(48)))
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
  mapStyle.setSelection(prefs.getInt("style2",0).coerceIn(0,7))
  mapStyle.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){if(styleReady)setMapStyle(pos)}}
 }

 private fun showPage(i:Int){
  pages.forEachIndexed{k,v->v.visibility=if(k==i)View.VISIBLE else View.GONE}
  tabs.forEachIndexed{k,t->t.setTextColor(if(k==i)Color.rgb(77,208,225) else Color.LTGRAY)}
  if(i!=1){val im=getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager;im.hideSoftInputFromWindow(query.windowToken,0)}
 }
 private fun goTo(lat:Double,lon:Double,zoom:Double){showPage(0);map.controller.setZoom(zoom);map.controller.animateTo(GeoPoint(lat,lon))}
 private fun onGroupTap(g:List<Meteorite>,center:GeoPoint){
  showGroup(g)
 }

 private fun locate(follow:Boolean){
  if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),if(follow)5001 else 5003);return}
  if(myLoc==null){val ov=MyLocationNewOverlay(GpsMyLocationProvider(this),map);myLoc=ov;map.overlays.add(ov)}
  val o=myLoc?:return
  o.enableMyLocation();if(follow)o.enableFollowLocation();if(follow)say("جارٍ تحديد موقعك بالـGPS… تأكد أن الموقع مفعّل في الهاتف.")
  if(follow)o.runOnFirstFix{runOnUiThread{val p=o.myLocation;if(p!=null){map.controller.setZoom(13.0);map.controller.animateTo(p);say("تم تحديد موقعك.")}}}
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==5002){if(grantResults.any{it==PackageManager.PERMISSION_GRANTED})startAlerts() else say("صلاحية الإشعارات مرفوضة، فلن تصلك التنبيهات.")}
  if(requestCode==5010){val a=pendingLoc;pendingLoc=null;if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)a?.invoke() else say("صلاحية الموقع مرفوضة.")}
  if(requestCode==5003){if(grantResults.any{it==PackageManager.PERMISSION_GRANTED})locate(false)}
  if(requestCode==5001){if(grantResults.any{it==PackageManager.PERMISSION_GRANTED})locate(true) else say("صلاحية الموقع مرفوضة. فعّلها من إعدادات التطبيق.")}
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
 private fun downloadArea(bb:BoundingBox,zmin:Int,zmax:Int,label:String){
  if(downloading){say("هناك تحميل جارٍ.");return}
  if(offline){say("فعّل وضع الاتصال أولاً.");return}
  val srcs=listOf(esri("Esri World Imagery","World_Imagery",".jpg"),esri("Esri Boundaries Places","Reference/World_Boundaries_and_Places",".png"),esri("Esri Transportation","Reference/World_Transportation",".png"))
  val total=try{CacheManager(srcs[0],SqlTileWriter(),zmin,zmax).possibleTilesInArea(bb,zmin,zmax)}catch(e:Exception){0}
  if(total>30000){say("المنطقة كبيرة جداً ($total مربع). قرّب الخريطة على منطقة أصغر ثم أعد المحاولة.");return}
  val mb=total*3*25/1024
  AlertDialog.Builder(this).setTitle("تحميل: $label").setMessage("المربعات: $total لكل طبقة × 3 طبقات.\nالحجم التقريبي: ${mb} ميغابايت.\nيُنصح بالواي فاي.")
   .setPositiveButton("ابدأ"){_,_->
    downloading=true
    var idx=0
    fun next(){
     if(idx>=srcs.size){downloading=false;dlInfo.text="✅ اكتمل تحميل: $label. يمكنك الآن تفعيل وضع عدم الاتصال.";return}
     val names=listOf("الأقمار الصناعية","أسماء الولايات والأماكن","الطرق")
     val cm=CacheManager(srcs[idx],SqlTileWriter(),zmin,zmax)
     cm.downloadAreaAsync(this,bb,zmin,zmax,object:CacheManager.CacheManagerCallback{
      override fun onTaskComplete(){idx++;next()}
      override fun onTaskFailed(errors:Int){dlInfo.text="طبقة ${names[idx]}: انتهت مع $errors أخطاء (ربما انقطع النت)";idx++;next()}
      override fun updateProgress(progress:Int,currentZoomLevel:Int,zoomMin:Int,zoomMax:Int){dlInfo.text="تحميل ${names[idx]}: $progress / $total (zoom $currentZoomLevel)"}
      override fun downloadStarted(){}
      override fun setPossibleTilesInArea(total:Int){}
     })
    }
    next()
   }.setNegativeButton("إلغاء",null).show()
 }
 private fun loadPlaces(){
  worker.execute{try{
   if(!placesFile.exists())return@execute
   val l=placesFile.useLines(Charsets.UTF_8){ls->ls.mapNotNull{ln->val t=ln.split('\t');if(t.size<4)null else{val la=t[0].toDoubleOrNull();val lo=t[1].toDoubleOrNull();if(la==null||lo==null)null else Place(la,lo,t[2],t[3],t.getOrElse(4){""})}}.toList()}
   runOnUiThread{places.setData(l);map.invalidate()}
  }catch(_:Exception){}}
 }
 private fun showPlace(p:Place){
  AlertDialog.Builder(this).setTitle(p.name)
   .setMessage("${PlacesOverlay.kindAr(p.kind)}"+(if(p.alt.isNotBlank()&&p.alt!=p.name)"\n${p.alt}" else "")+"\n\n"+Geo.full(p.lat,p.lon)+"\n\nالمصدر: OpenStreetMap")
   .setPositiveButton("🧭 اتجه إليه"){_,_->setNav(p.lat,p.lon,p.name)}
   .setNeutralButton("📌 احفظ كعلامة"){_,_->editWaypoint(null,p.lat,p.lon,p.name)}
   .setNegativeButton("إغلاق",null).show()
 }
 private fun post(url:String,body:String):String{
  val c=URL(url).openConnection() as HttpURLConnection
  c.requestMethod="POST";c.doOutput=true;c.connectTimeout=30000;c.readTimeout=180000
  c.setRequestProperty("User-Agent","MeteoriteAtlas-Android/1.7");c.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
  try{
   c.outputStream.use{it.write(("data="+URLEncoder.encode(body,"UTF-8")).toByteArray(Charsets.UTF_8))}
   if(c.responseCode !in 200..299)throw Exception("HTTP ${c.responseCode}")
   return c.inputStream.bufferedReader(Charsets.UTF_8).use{it.readText()}
  }finally{c.disconnect()}
 }
 private fun downloadPlaces(){
  if(downloading){say("هناك تحميل جارٍ.");return}
  if(offline){say("فعّل وضع الاتصال أولاً.");return}
  downloading=true;dlInfo.text="جارٍ تحميل أسماء القرى والآبار… (قد يستغرق دقائق)"
  worker.execute{
   val latE=doubleArrayOf(19.0,24.5,30.0,37.4);val lonE=doubleArrayOf(-8.8,-2.0,5.0,12.2)
   val servers=listOf("https://overpass-api.de/api/interpreter","https://overpass.kumi.systems/api/interpreter")
   val sb=StringBuilder();var n=0;var failed=0;var done=0
   for(i in 0 until 3)for(j in 0 until 3){
    val s0=latE[i];val n0=latE[i+1];val w0=lonE[j];val e0=lonE[j+1]
    val q="""[out:json][timeout:150];(node["place"~"^(town|village|hamlet|locality|isolated_dwelling)$"]["name"]($s0,$w0,$n0,$e0);node["man_made"="water_well"]["name"]($s0,$w0,$n0,$e0);node["natural"="spring"]["name"]($s0,$w0,$n0,$e0);node["amenity"="drinking_water"]["name"]($s0,$w0,$n0,$e0););out;"""
    var ok=false
    for(sv in servers){
     try{
      val arr=JSONObject(post(sv,q)).optJSONArray("elements")?:continue
      for(k in 0 until arr.length()){
       val o=arr.optJSONObject(k)?:continue
       val tg=o.optJSONObject("tags")?:continue
       val kind=when{tg.has("place")->tg.optString("place");tg.optString("man_made")=="water_well"->"well";tg.optString("natural")=="spring"->"spring";else->"water"}
       val nm=tg.optString("name");val ar=tg.optString("name:ar")
       val main=(if(ar.isNotBlank())ar else nm).replace('\t',' ').replace('\n',' ')
       val alt=(if(ar.isNotBlank()&&nm!=ar)nm else tg.optString("name:fr")).replace('\t',' ').replace('\n',' ')
       if(main.isBlank())continue
       sb.append(o.optDouble("lat")).append('\t').append(o.optDouble("lon")).append('\t').append(kind).append('\t').append(main).append('\t').append(alt).append('\n');n++
      }
      ok=true;break
     }catch(_:Exception){}
    }
    if(!ok)failed++
    done++
    runOnUiThread{dlInfo.text="تحميل الأسماء: $done / 9 (${n} اسم)"}
   }
   try{if(n>0){placesFile.writeText(sb.toString(),Charsets.UTF_8)}}catch(_:Exception){}
   runOnUiThread{
    downloading=false
    dlInfo.text=if(n>0)"✅ حُمّل $n اسم (قرى وآبار وعيون)"+(if(failed>0)" — فشل $failed من 9 أجزاء، أعد المحاولة لاحقاً" else "")+". تظهر عند التقريب وتعمل بدون إنترنت." else "تعذر التحميل. تأكد من الإنترنت وأعد المحاولة."
    loadPlaces()
   }
  }
 }
 private fun coordMenu(){
  val c=map.mapCenter;val lat=c.latitude;val lon=c.longitude
  AlertDialog.Builder(this).setTitle(Geo.dec(lat,lon)).setItems(arrayOf("📋 نسخ الإحداثيات","⛰ ارتفاع هذه النقطة (يحتاج إنترنت)","📌 وضع علامة هنا")){_,i->
   when(i){
    0->{val t=Geo.dec(lat,lon);(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("coords",t));say("نُسخت: $t")}
    1->{say("جارٍ جلب الارتفاع…");worker.execute{try{
      val e=JSONObject(get("https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lon")).getJSONArray("elevation").getDouble(0)
      runOnUiThread{AlertDialog.Builder(this).setTitle("⛰ الارتفاع").setMessage("${e.toInt()} م فوق سطح البحر\n(نموذج ارتفاع رقمي بدقة ~90 م)\n\n${Geo.full(lat,lon)}").setPositiveButton("حسناً",null).show()}
     }catch(ex:Exception){runOnUiThread{say("تعذر جلب الارتفاع: ${ex.message}")}}}}
    else->editWaypoint(null,lat,lon)
   }
  }.setNegativeButton("إغلاق",null).show()
 }
 private fun needLoc(a:()->Unit){
  if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){a();return}
  pendingLoc=a
  val perms=ArrayList<String>();perms.add(Manifest.permission.ACCESS_FINE_LOCATION);perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
  if(Build.VERSION.SDK_INT>=33)perms.add(Manifest.permission.POST_NOTIFICATIONS)
  requestPermissions(perms.toTypedArray(),5010)
 }
 private fun syncService(){
  val need=prefs.getBoolean("rec_on",false)||prefs.getBoolean("near_on",false)
  val i=Intent(this,TrackService::class.java)
  try{if(need){if(Build.VERSION.SDK_INT>=26)startForegroundService(i) else startService(i)}else stopService(i)}catch(e:Exception){say("تعذر تشغيل خدمة GPS: ${e.message}")}
 }
 private fun toggleRecording(){
  if(prefs.getBoolean("rec_on",false)){stopRecording();return}
  needLoc{
   try{File(filesDir,"track_current.csv").delete()}catch(_:Exception){}
   recSize=-1L;recPts=emptyList();routeOv.recTrack=emptyList()
   prefs.edit().putBoolean("rec_on",true).apply();syncService();locate(false)
   recFab.background=round(Color.rgb(198,40,40))
   say("بدأ تسجيل المسار (يستمر والشاشة مطفأة). اضغط ⏺ مرة أخرى للإيقاف والحفظ.")
  }
 }
 private fun stopRecording(){
  prefs.edit().putBoolean("rec_on",false).apply();syncService()
  recFab.background=round(Color.argb(235,16,35,45))
  val f=File(filesDir,"track_current.csv");val pts=Gpx.readCsv(f)
  routeOv.recTrack=emptyList();recSize=-1L
  if(pts.size>=2){
   val dir=File(filesDir,"tracks");dir.mkdirs()
   val name="track_"+SimpleDateFormat("yyyyMMdd_HHmm",Locale.US).format(Date())+".csv"
   Gpx.writeCsv(File(dir,name),pts);f.delete()
   say("حُفظ المسار: ${Geo.fmtDist(Gpx.length(pts))} • صعود ${Gpx.ascent(pts).toInt()} م. تجده في الإعدادات ← مساراتي.")
  }else{f.delete();say("المسار قصير جداً فلم يُحفظ.")}
  updateHud();map.invalidate()
 }
 private fun tracksDir()=File(filesDir,"tracks").apply{mkdirs()}
 private fun syncTracksOnMap(){
  routeOv.savedTracks=shownTracks.mapNotNull{n->val f=File(tracksDir(),n);if(f.exists())Gpx.readCsv(f).map{GeoPoint(it.lat,it.lon)} else null}
  map.invalidate()
 }
 private fun showTracks(){
  val fs=(tracksDir().listFiles()?.filter{it.name.endsWith(".csv")}?:emptyList()).sortedByDescending{it.name}
  if(fs.isEmpty()){AlertDialog.Builder(this).setTitle("🥾 مساراتي").setMessage("لا توجد مسارات محفوظة. اضغط زر ⏺ على الخريطة لتسجيل مسار.").setPositiveButton("حسناً",null).show();return}
  val labels=fs.map{f->val p=Gpx.readCsv(f);(if(f.name in shownTracks)"👁 " else "")+f.name.removeSuffix(".csv").removePrefix("track_")+" — "+Geo.fmtDist(Gpx.length(p))+" • ↑"+Gpx.ascent(p).toInt()+" م"}.toTypedArray()
  AlertDialog.Builder(this).setTitle("🥾 مساراتي (${fs.size})").setItems(labels){_,i->
   val f=fs[i];val on=f.name in shownTracks
   AlertDialog.Builder(this).setTitle(f.name.removeSuffix(".csv")).setItems(arrayOf(if(on)"إخفاء من الخريطة" else "عرض على الخريطة","🗑 حذف المسار")){_,k->
    if(k==0){
     if(on)shownTracks.remove(f.name) else {shownTracks.add(f.name);val p=Gpx.readCsv(f);if(p.isNotEmpty()){goTo(p[0].lat,p[0].lon,13.0)}}
     syncTracksOnMap();showPage(0)
    }else{f.delete();shownTracks.remove(f.name);syncTracksOnMap();say("تم حذف المسار.")}
   }.setNegativeButton("إغلاق",null).show()
  }.setNegativeButton("إغلاق",null).show()
 }
 private fun toggleNear(){
  if(prefs.getBoolean("near_on",false)){prefs.edit().putBoolean("near_on",false).apply();syncService();updateNearBtn();say("أُوقف تنبيه الاقتراب.");return}
  needLoc{prefs.edit().putBoolean("near_on",true).apply();syncService();updateNearBtn();say("تنبيه الاقتراب مفعّل: يهتز الهاتف ويُشعرك عند الاقتراب من علامة أو نيزك أو بئر (يعمل في الخلفية).")}
 }
 private fun updateNearBtn(){nearBtn.text=if(prefs.getBoolean("near_on",false))"🔔 تنبيه الاقتراب: مفعّل (اضغط للإيقاف)" else "🔕 تنبيه الاقتراب: متوقف (اضغط للتفعيل)"}
 private fun exportGpx(){
  val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/gpx+xml";putExtra(Intent.EXTRA_TITLE,"meteorite_atlas.gpx")}
  startActivityForResult(i,4201)
 }
 private fun importGpx(){
  val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"}
  startActivityForResult(i,4202)
 }
 private fun importMbtiles(){
  val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"}
  startActivityForResult(i,4203)
 }
 private fun applyMbtiles():Boolean{
  val path=prefs.getString("mbtiles",null)?:return false
  val f=File(path);if(!f.exists())return false
  return try{
   val prov=OfflineTileProvider(SimpleRegisterReceiver(this),arrayOf(f))
   map.setTileProvider(prov);usingMbtiles=true
   var ok=false
   val arch=prov.archives
   if(arch.isNotEmpty()){val ts=arch[0].tileSources;if(ts.isNotEmpty()){map.setTileSource(FileBasedTileSource.getSource(ts.iterator().next()));ok=true}}
   if(!ok)map.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
   map.setUseDataConnection(false);true
  }catch(e:Exception){false}
 }
 private fun updateCoord(){
  if(!::coordChip.isInitialized)return
  val c=map.mapCenter
  var gps=""
  try{val fx=myLoc?.lastFix;if(fx!=null)gps="\nGPS: ±${fx.accuracy.toInt()} م"+(if(fx.hasAltitude())" • ارتفاع ${fx.altitude.toInt()} م" else "")}catch(_:Exception){}
  coordChip.text="✛ "+Geo.dec(c.latitude,c.longitude)+"\n"+Geo.dms(c.latitude,c.longitude)+gps
 }
 private fun saveWps(){WpStore.save(prefs,waypoints);routeOv.wps=ArrayList(waypoints);map.invalidate()}
 private fun setNav(lat:Double,lon:Double,name:String){
  navTarget=GeoPoint(lat,lon);navName=name;routeOv.navTarget=navTarget
  locate(false);showPage(0);updateHud();map.invalidate()
  say("التوجيه إلى «$name»: يظهر سهم من موقعك إلى الهدف. فعّل الموقع (GPS).")
 }
 private fun centerPt()=GeoPoint(map.mapCenter.latitude,map.mapCenter.longitude)
 private fun syncRuler(){routeOv.ruler=ArrayList(rulerPts);updateHud();map.invalidate()}
 private fun toggleRuler(){
  rulerOn=!rulerOn;routeOv.rulerMode=rulerOn
  rulerFab.background=round(if(rulerOn)Color.rgb(198,40,40) else Color.argb(235,16,35,45))
  rulerPts.clear()
  if(rulerOn){rulerPts.add(centerPt());say("المسطرة: النقطة A ثُبّتت في مركز الشاشة (✛). حرّك الخريطة فيتبعك السهم والمسافة. «📍 ثبّت» لتثبيت نقطة جديدة.")}
  syncRuler()
 }
 private fun rulerRestart(){rulerPts.clear();rulerPts.add(centerPt());syncRuler()}
 private fun rulerFix(){
  val c=centerPt();val l=rulerPts.lastOrNull()
  if(l!=null&&Geo.dist(l.latitude,l.longitude,c.latitude,c.longitude)<2.0){say("حرّك الخريطة أولاً.");return}
  rulerPts.add(c);syncRuler()
 }
 private fun rulerUndo(){if(rulerPts.size>1)rulerPts.removeAt(rulerPts.size-1);syncRuler()}
 private fun rulerAdd(g:GeoPoint){map.controller.animateTo(g);say("حُرّكت الخريطة إلى العلامة؛ اضغط «📍 ثبّت» لتثبيتها.")}
 private fun updateHud(){
  if(!::hudBox.isInitialized)return
  val lines=ArrayList<String>()
  val t=navTarget
  if(t!=null){
   val me=myLoc?.myLocation
   val fLat=me?.latitude?:map.mapCenter.latitude;val fLon=me?.longitude?:map.mapCenter.longitude
   val dm=Geo.dist(fLat,fLon,t.latitude,t.longitude);val br=Geo.bearing(fLat,fLon,t.latitude,t.longitude)
   navBearing=br.toFloat();navArrow.rotation=((navBearing-heading)+360f)%360f
   lines.add("🧭 $navName: ${Geo.fmtDist(dm)} • ${br.toInt()}° ${Geo.cardinal(br)}"+(if(me==null)"\n(من مركز الشاشة — شغّل GPS بزر 📍)" else ""))
  }
  if(rulerOn&&rulerPts.isNotEmpty()){
   var total=0.0
   for(i in 1 until rulerPts.size)total+=Geo.dist(rulerPts[i-1].latitude,rulerPts[i-1].longitude,rulerPts[i].latitude,rulerPts[i].longitude)
   val l=rulerPts[rulerPts.size-1];val c=map.mapCenter
   val dm=Geo.dist(l.latitude,l.longitude,c.latitude,c.longitude);val br=Geo.bearing(l.latitude,l.longitude,c.latitude,c.longitude)
   lines.add("📏 من النقطة ${if(rulerPts.size==1)"A" else ('A'+rulerPts.size-1).toString()}: ${Geo.fmtDist(dm)} • ${br.toInt()}° ${Geo.cardinal(br)}\nالمجموع مع المثبّت: ${Geo.fmtDist(total+dm)}")
  }
  if(prefs.getBoolean("rec_on",false))lines.add("⏺ تسجيل المسار: ${Geo.fmtDist(Gpx.length(recPts))} • ${recPts.size} نقطة • صعود ${Gpx.ascent(recPts).toInt()} م")
  hudBox.visibility=if(lines.isEmpty())View.GONE else View.VISIBLE
  hud.text=lines.joinToString("\n")
  rulerRow.visibility=if(rulerOn)View.VISIBLE else View.GONE
  navRow.visibility=if(navTarget!=null)View.VISIBLE else View.GONE
 }
 private fun addWaypointMenu(){
  val items=arrayOf("📍 في موقعي الحالي (GPS)","✛ في مركز الشاشة","⌨ إدخال إحداثيات يدوياً")
  AlertDialog.Builder(this).setTitle("إضافة علامة").setItems(items){_,i->
   val me=myLoc?.myLocation;val c=map.mapCenter
   when(i){
    0->if(me!=null)editWaypoint(null,me.latitude,me.longitude) else {locate(false);say("لا يوجد تحديد GPS بعد. انتظر ثواني ثم أعد المحاولة.")}
    else->editWaypoint(null,c.latitude,c.longitude)
   }
  }.setNegativeButton("إلغاء",null).show()
  Toast.makeText(this,"نصيحة: اضغط مطولاً على أي مكان في الخريطة لوضع علامة هناك.",Toast.LENGTH_LONG).show()
 }
 private fun editWaypoint(w:Waypoint?,lat:Double,lon:Double,prefName:String?=null){
  val box=LinearLayout(this).apply{orientation=1;setPadding(dp(18),dp(8),dp(18),0)}
  val num=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
  val name=EditText(this).apply{hint="اسم العلامة";setText(w?.name?:(prefName?:"علامة ${waypoints.size+1}"))}
  val note=EditText(this).apply{hint="ملاحظة (اختياري)";setText(w?.note?:"")}
  val latE=EditText(this).apply{hint="خط العرض Lat";inputType=num;setText(String.format(Locale.US,"%.6f",w?.lat?:lat))}
  val lonE=EditText(this).apply{hint="خط الطول Lon";inputType=num;setText(String.format(Locale.US,"%.6f",w?.lon?:lon))}
  val iconSp=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,WpStore.ICONS.mapIndexed{i,e->"$e  ${WpStore.ICON_NAMES[i]}"});setSelection(w?.icon?:0)}
  val colSp=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,WpStore.COLOR_NAMES);setSelection(w?.color?:0)}
  box.addView(name);box.addView(note);box.addView(latE);box.addView(lonE)
  box.addView(TextView(this).apply{text="الرمز";setPadding(0,dp(8),0,0)});box.addView(iconSp)
  box.addView(TextView(this).apply{text="اللون";setPadding(0,dp(8),0,0)});box.addView(colSp)
  val b=AlertDialog.Builder(this).setTitle(if(w==null)"علامة جديدة" else "تعديل العلامة").setView(ScrollView(this).apply{addView(box)})
   .setPositiveButton("حفظ"){_,_->
    val la=latE.text.toString().replace(',','.').toDoubleOrNull();val lo=lonE.text.toString().replace(',','.').toDoubleOrNull()
    if(la==null||lo==null||la !in -90.0..90.0||lo !in -180.0..180.0){say("إحداثيات غير صالحة.");return@setPositiveButton}
    val nm=name.text.toString().trim().ifBlank{"علامة"}
    if(w==null)waypoints.add(Waypoint(System.currentTimeMillis(),nm,note.text.toString().trim(),la,lo,iconSp.selectedItemPosition,colSp.selectedItemPosition))
    else{w.name=nm;w.note=note.text.toString().trim();w.lat=la;w.lon=lo;w.icon=iconSp.selectedItemPosition;w.color=colSp.selectedItemPosition}
    saveWps();say("تم حفظ العلامة.")
   }.setNegativeButton("إلغاء",null)
  if(w!=null)b.setNeutralButton("🗑 حذف"){_,_->waypoints.remove(w);saveWps();say("تم الحذف.")}
  b.show()
 }
 private fun showWaypoint(w:Waypoint){
  val me=myLoc?.myLocation
  val far=if(me!=null){val dm=Geo.dist(me.latitude,me.longitude,w.lat,w.lon);val br=Geo.bearing(me.latitude,me.longitude,w.lat,w.lon);"\nمنك: ${Geo.fmtDist(dm)} • ${br.toInt()}° ${Geo.cardinal(br)}"} else ""
  AlertDialog.Builder(this).setTitle("${WpStore.ICONS[w.icon.coerceIn(0,WpStore.ICONS.size-1)]} ${w.name}")
   .setMessage((if(w.note.isNotBlank())w.note+"\n\n" else "")+Geo.full(w.lat,w.lon)+far)
   .setPositiveButton("🧭 اتجه إليه"){_,_->setNav(w.lat,w.lon,w.name)}
   .setNeutralButton("✏ تعديل"){_,_->editWaypoint(w,w.lat,w.lon)}
   .setNegativeButton("إغلاق",null).show()
 }
 private fun showWaypointList(){
  if(waypoints.isEmpty()){AlertDialog.Builder(this).setTitle("علاماتي").setMessage("لا توجد علامات. اضغط مطولاً على الخريطة أو زر ➕ لإضافة علامة.").setPositiveButton("حسناً"){_,_->showPage(0)}.show();return}
  val labels=waypoints.map{"${WpStore.ICONS[it.icon.coerceIn(0,WpStore.ICONS.size-1)]} ${it.name} — ${String.format(Locale.US,"%.4f, %.4f",it.lat,it.lon)}"}.toTypedArray()
  AlertDialog.Builder(this).setTitle("علاماتي (${waypoints.size})").setItems(labels){_,i->showWaypoint(waypoints[i])}.setNegativeButton("إغلاق",null).show()
 }
 private fun updateOfflineButton(){offlineButton.text=if(offline)"● وضع عدم الاتصال: مفعّل (اضغط للاتصال)" else "● وضع الاتصال: مفعّل (اضغط لعدم الاتصال)"}

 private fun esri(name:String,path:String,ext:String)=object:OnlineTileSourceBase(name,0,17,256,ext,arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$path/MapServer/tile/")){
  override fun getTileURLString(tile:Long):String{val z=MapTileIndex.getZoom(tile);val x=MapTileIndex.getX(tile);val y=MapTileIndex.getY(tile);return "https://server.arcgisonline.com/ArcGIS/rest/services/$path/MapServer/tile/$z/$y/$x"}
 }
 private fun labelLayer(src:OnlineTileSourceBase):TilesOverlay{
  val ov=TilesOverlay(MapTileProviderBasic(this,src),this)
  ov.setLoadingBackgroundColor(Color.TRANSPARENT);ov.setLoadingLineColor(Color.TRANSPARENT)
  return ov
 }
 private fun xyz(name:String,maxZ:Int,ext:String,prefix:String,suffix:String)=object:OnlineTileSourceBase(name,0,maxZ,256,ext,arrayOf(prefix)){
  override fun getTileURLString(tile:Long):String{val z=MapTileIndex.getZoom(tile);val x=MapTileIndex.getX(tile);val y=MapTileIndex.getY(tile);return "$prefix$z/$y/$x$suffix"}
 }
 private fun addEsriLabels(){
  val places=labelLayer(esri("Esri Boundaries Places","Reference/World_Boundaries_and_Places",".png"))
  val roads=labelLayer(esri("Esri Transportation","Reference/World_Transportation",".png"))
  map.overlays.add(0,roads);map.overlays.add(1,places)
  labelOverlays.add(roads);labelOverlays.add(places)
 }
 private fun setMapStyle(i0:Int){
  if(!::map.isInitialized)return
  var i=i0
  labelOverlays.forEach{map.overlays.remove(it)};labelOverlays.clear()
  if(i==7){
   if(!applyMbtiles()){say("لم تستورد ملف MBTiles بعد. استورده من الإعدادات.");i=0}
  }
  if(i!=7&&usingMbtiles){map.setTileProvider(MapTileProviderBasic(applicationContext));usingMbtiles=false;map.setUseDataConnection(!offline)}
  when(i){
   0->{map.setTileSource(esri("Esri World Imagery","World_Imagery",".jpg"));addEsriLabels()}
   1->map.setTileSource(TileSourceFactory.MAPNIK)
   2->map.setTileSource(esri("Esri World Imagery","World_Imagery",".jpg"))
   3->map.setTileSource(XYTileSource("OpenTopoMap",0,17,256,".png",arrayOf("https://a.tile.opentopomap.org/","https://b.tile.opentopomap.org/","https://c.tile.opentopomap.org/")))
   4->{map.setTileSource(xyz("EOX Sentinel-2 2021",13,".jpg","https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2021_3857/default/g/",".jpg"));addEsriLabels()}
   5->{map.setTileSource(xyz("Esri Clarity",17,".jpg","https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/",""));addEsriLabels()}
   6->{map.setTileSource(esri("Esri Hillshade","Elevation/World_Hillshade",".jpg"));addEsriLabels()}
   else->{}
  }
  try{map.overlayManager.tilesOverlay.apply{setLoadingBackgroundColor(Color.rgb(22,34,42));setLoadingLineColor(Color.rgb(40,58,68))}}catch(_:Exception){}
  prefs.edit().putInt("style2",i).apply();map.invalidate()
 }
 private fun load(force:Boolean){
  worker.execute{try{
   val base=assets.open("meteorites.csv").bufferedReader(Charsets.UTF_8).use{it.readText()}
   var all=parseCsv(base)
   if(officialCache.exists()&&officialCache.length()>1000){try{all=(all+parseCsv(officialCache.readText(Charsets.UTF_8))).distinctBy{it.name.lowercase(Locale.ROOT)}}catch(_:Exception){}}
   if(all.isEmpty())throw Exception("لم تُقرأ أي سجلات")
   try{localDb.replaceAll(all)}catch(_:Exception){}
   runOnUiThread{data=all;applyFilter();if(force)say("أُعيد تحميل ${all.size} نيزكاً من القاعدة المحلية.")}
  }catch(e:Exception){runOnUiThread{countChip.text="تعذر تحميل البيانات: ${e.message}";say("تعذر تحميل البيانات: ${e.message}")}}}
 }
 private fun refreshFireList(){
  fireAdapter.clear()
  fireAdapter.addAll(fireballEvents.take(100).map{e->"${e.dateUtc} UTC — ${String.format(Locale.US,"%.2f",e.latitude)}, ${String.format(Locale.US,"%.2f",e.longitude)}"})
  fireInfo.text="الأحداث: ${fireballEvents.size} (من NASA/JPL، ليست بثاً لحظياً). تظهر على الخريطة كنقاط صفراء. اضغط حدثاً للتفاصيل."
 }
 private fun refreshFireballs(silent:Boolean=false){
  if(!silent)say("جارٍ جلب أحدث الأحداث من NASA/JPL…")
  worker.execute {
   try {
    val raw=get("https://ssd-api.jpl.nasa.gov/fireball.api?req-loc=true&limit=100&sort=-date")
    val parsed=parseFireballs(raw)
    if(parsed.isEmpty()) throw Exception("لم تُرجع الخدمة أحداثاً ذات إحداثيات")
    val temp=File(filesDir,"nasa_fireballs_cache.json.tmp")
    temp.writeText(raw,Charsets.UTF_8)
    if(!temp.renameTo(fireballCache)){fireballCache.writeText(raw,Charsets.UTF_8);temp.delete()}
    runOnUiThread {
     val old=fireballEvents.map{it.dateUtc}.toSet()
     val fresh=if(old.isEmpty())emptyList() else parsed.filter{it.dateUtc !in old}
     fireballEvents=parsed;points.fires=parsed;refreshFireList();map.invalidate()
     if(fresh.isNotEmpty())say("☄ ${fresh.size} حدث جديد من NASA على الخريطة. أحدثه: ${fresh[0].dateUtc} UTC")
     else if(!silent)say("تم تحديث ${parsed.size} حدثاً.")
    }
   }catch(e:Exception){
    if(!silent)runOnUiThread {say("تعذر الاتصال بـ NASA/JPL: ${e.message}")}
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
  val txt="☄ ${shown.size} نيزك جزائري من أصل ${data.size}"
  countChip.text=txt;listInfo.text=txt+" — اضغط على أي نيزك لعرض معلوماته."
  map.invalidate()
 }
 private fun showMeteorite(m:Meteorite){
  val fell=if(isFell(m))"سقط وشوهد (Fell)" else "عُثر عليه (Found)"
  AlertDialog.Builder(this).setTitle("☄ "+m.name.ifBlank{"نيزك بلا اسم"})
   .setMessage("الاسم: ${m.name}\nالموقع: ${m.place.ifBlank{"غير محدد"}}\nالتصنيف: ${m.cls.ifBlank{"غير متاح"}}\nالسنة: ${m.year.ifBlank{"غير معروفة"}}\nالكتلة: ${m.mass.ifBlank{"غير متاحة"}} غرام\nالحالة: ${m.fall.ifBlank{fell}}\n\nالإحداثيات:\n${Geo.full(m.lat,m.lon)}\n\nالمصدر: Meteoritical Bulletin Database")
   .setPositiveButton("عرض على الخريطة"){_,_->goTo(m.lat,m.lon,12.0)}
   .setNeutralButton("🧭 اتجه إليه"){_,_->setNav(m.lat,m.lon,m.name)}
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
  if(requestCode==4201){worker.execute{try{
   val tr=(tracksDir().listFiles()?.filter{it.name.endsWith(".csv")}?:emptyList()).sortedBy{it.name}.map{Pair(it.name.removeSuffix(".csv"),Gpx.readCsv(it))}
   val text=Gpx.build(waypoints,tr)
   contentResolver.openOutputStream(uri)?.use{it.write(text.toByteArray(Charsets.UTF_8))}?:throw Exception("تعذر الكتابة")
   runOnUiThread{say("تم التصدير: ${waypoints.size} علامة و${tr.size} مسار.")}
  }catch(e:Exception){runOnUiThread{say("فشل التصدير: ${e.message}")}}};return}
  if(requestCode==4202){worker.execute{try{
   val raw=contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}?:throw Exception("تعذر فتح الملف")
   val (w,t)=Gpx.parse(raw)
   var addedW=0
   runOnUiThread{
    for(x in w){if(waypoints.none{it.name==x.name&&Geo.dist(it.lat,it.lon,x.lat,x.lon)<2.0}){waypoints.add(x);addedW++}}
    saveWps()
   }
   var k=0
   for((name,pts) in t){k++;Gpx.writeCsv(File(tracksDir(),"track_gpx_"+System.currentTimeMillis()+"_"+k+".csv"),pts)}
   runOnUiThread{say("تم الاستيراد: ${w.size} علامة و${t.size} مسار.")}
  }catch(e:Exception){runOnUiThread{say("فشل الاستيراد: ${e.message}")}}};return}
  if(requestCode==4203){say("جارٍ نسخ الخريطة…");worker.execute{try{
   val dst=File(filesDir,"offline_map.mbtiles")
   contentResolver.openInputStream(uri)?.use{i->dst.outputStream().use{o->i.copyTo(o)}}?:throw Exception("تعذر فتح الملف")
   prefs.edit().putString("mbtiles",dst.absolutePath).apply()
   runOnUiThread{say("تم استيراد الخريطة.");mapStyle.setSelection(7);setMapStyle(7)}
  }catch(e:Exception){runOnUiThread{say("فشل استيراد الخريطة: ${e.message}")}}};return}
  if(requestCode==4102){say("جارٍ فحص واستيراد CSV…");worker.execute{try{
   val raw=contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}?:throw Exception("تعذر فتح الملف")
   val parsed=parseCsv(raw);if(parsed.isEmpty())throw Exception("لم أجد نيازك جزائرية بإحداثيات في الملف؛ لم تُستبدل القاعدة.")
   val tmp=File(filesDir,"metbull_official.csv.tmp");tmp.writeText(raw,Charsets.UTF_8);if(!tmp.renameTo(officialCache)){officialCache.writeText(raw,Charsets.UTF_8);tmp.delete()}
   localDb.replaceAll(parsed);runOnUiThread{data=parsed;applyFilter();say("تم استيراد ${parsed.size} سجل.")}
  }catch(e:Exception){runOnUiThread{say("فشل الاستيراد: ${e.message}")}}}}
 }
 override fun onResume(){super.onResume();if(::map.isInitialized)map.onResume();myLoc?.enableMyLocation();try{compass?.enableCompass()}catch(_:Exception){};try{sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let{sm.registerListener(sensorL,it,SensorManager.SENSOR_DELAY_UI)}}catch(_:Exception){}}
 override fun onPause(){try{sm.unregisterListener(sensorL)}catch(_:Exception){};myLoc?.disableMyLocation();try{compass?.disableCompass()}catch(_:Exception){};if(::map.isInitialized)map.onPause();super.onPause()}
 override fun onDestroy(){ui.removeCallbacks(tick);worker.shutdownNow();if(::map.isInitialized)map.onDetach();super.onDestroy()}
}

private class MeteoriteDb(context:Context):SQLiteOpenHelper(context,"meteorite_atlas.db",null,1){
 override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE meteorites (name TEXT, place TEXT, cls TEXT, year TEXT, mass TEXT, fall TEXT, lat REAL, lon REAL, PRIMARY KEY(name,lat,lon))")}
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
 fun replaceAll(items:List<Meteorite>){val db=writableDatabase;db.beginTransaction();try{db.delete("meteorites",null,null);val stmt=db.compileStatement("INSERT OR REPLACE INTO meteorites(name,place,cls,year,mass,fall,lat,lon) VALUES(?,?,?,?,?,?,?,?)");for(m in items){stmt.clearBindings();stmt.bindString(1,m.name);stmt.bindString(2,m.place);stmt.bindString(3,m.cls);stmt.bindString(4,m.year);stmt.bindString(5,m.mass);stmt.bindString(6,m.fall);stmt.bindDouble(7,m.lat);stmt.bindDouble(8,m.lon);stmt.executeInsert()};stmt.close();db.setTransactionSuccessful()}finally{db.endTransaction()}}
}
