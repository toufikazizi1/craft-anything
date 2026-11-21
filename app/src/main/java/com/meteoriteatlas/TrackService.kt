package com.meteoriteatlas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import java.io.File
import kotlin.math.abs

/** Foreground GPS service: records the track and warns when near waypoints, meteorites or water points. */
class TrackService:Service() {
 private class Target(val name:String,val lat:Double,val lon:Double,val kind:String)
 private var targets=emptyList<Target>()
 private var loadedAt=0L
 private val lastAlert=HashMap<String,Long>()
 private val prefs by lazy{getSharedPreferences("atlas",Context.MODE_PRIVATE)}
 private lateinit var lm:LocationManager

 private val listener=object:LocationListener{
  override fun onLocationChanged(l:Location){handle(l)}
  override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
  override fun onProviderEnabled(provider:String){}
  override fun onProviderDisabled(provider:String){}
 }
 override fun onBind(intent:Intent?):IBinder?=null

 private fun channels(){
  if(Build.VERSION.SDK_INT>=26){
   val nm=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
   nm.createNotificationChannel(NotificationChannel("dz_track","تسجيل المسار",NotificationManager.IMPORTANCE_LOW))
   nm.createNotificationChannel(NotificationChannel("dz_near","تنبيه الاقتراب",NotificationManager.IMPORTANCE_HIGH).apply{enableVibration(true)})
  }
 }
 @Suppress("DEPRECATION")
 private fun build(channel:String,title:String,text:String,ongoing:Boolean):Notification{
  val b=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,channel) else Notification.Builder(this)
  b.setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(title).setContentText(text).setOngoing(ongoing)
  if(!ongoing)b.setAutoCancel(true)
  if(Build.VERSION.SDK_INT<26&&!ongoing)b.setDefaults(Notification.DEFAULT_ALL)
  return b.build()
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  channels()
  lm=getSystemService(Context.LOCATION_SERVICE) as LocationManager
  val rec=prefs.getBoolean("rec_on",false);val near=prefs.getBoolean("near_on",false)
  val text=(if(rec)"يُسجَّل مسارك. " else "")+(if(near)"تنبيه الاقتراب مفعّل." else "")
  val n=build("dz_track","أطلس النيازك — GPS",text.ifBlank{"GPS يعمل"},true)
  try{
   if(Build.VERSION.SDK_INT>=29)startForeground(77,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(77,n)
  }catch(e:Exception){stopSelf();return START_NOT_STICKY}
  try{lm.removeUpdates(listener);lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,3000L,3f,listener)}catch(e:SecurityException){stopSelf();return START_NOT_STICKY}catch(e:Exception){}
  return START_STICKY
 }
 override fun onDestroy(){try{lm.removeUpdates(listener)}catch(_:Exception){};super.onDestroy()}

 private fun splitCsv(s:String):List<String>{
  val out=ArrayList<String>();val sb=StringBuilder();var q=false
  for(ch in s){if(ch=='"')q=!q else if(ch==','&&!q){out.add(sb.toString());sb.setLength(0)}else sb.append(ch)}
  out.add(sb.toString());return out
 }
 private fun loadTargets(){
  val out=ArrayList<Target>()
  for(w in WpStore.load(prefs))out.add(Target(w.name,w.lat,w.lon,"علامتك"))
  try{assets.open("meteorites.csv").bufferedReader(Charsets.UTF_8).useLines{ls->ls.drop(1).forEach{ln->val c=splitCsv(ln);if(c.size>=8){val la=c[6].toDoubleOrNull();val lo=c[7].toDoubleOrNull();if(la!=null&&lo!=null)out.add(Target(c[0],la,lo,"نيزك"))}}}}catch(_:Exception){}
  try{val f=File(filesDir,"places.tsv");if(f.exists())f.useLines(Charsets.UTF_8){ls->ls.forEach{ln->val t=ln.split('\t');if(t.size>=4&&(t[2]=="well"||t[2]=="spring"||t[2]=="water")){val la=t[0].toDoubleOrNull();val lo=t[1].toDoubleOrNull();if(la!=null&&lo!=null)out.add(Target(t[3],la,lo,"ماء"))}}}}catch(_:Exception){}
  targets=out;loadedAt=System.currentTimeMillis()
 }
 private fun handle(l:Location){
  val now=System.currentTimeMillis()
  if(prefs.getBoolean("rec_on",false)&&(!l.hasAccuracy()||l.accuracy<=60f)){
   try{File(filesDir,"track_current.csv").appendText("$now,${l.latitude},${l.longitude},${if(l.hasAltitude())l.altitude else 0.0}\n")}catch(_:Exception){}
  }
  if(prefs.getBoolean("near_on",false)){
   if(now-loadedAt>60000L||targets.isEmpty())loadTargets()
   val radius=prefs.getInt("near_radius",300)
   val due=ArrayList<Pair<Target,Double>>()
   for(t in targets){
    if(abs(t.lat-l.latitude)>0.05||abs(t.lon-l.longitude)>0.06)continue
    val d=Geo.dist(l.latitude,l.longitude,t.lat,t.lon)
    if(d>radius)continue
    val key=t.kind+t.name+t.lat
    val last=lastAlert[key]
    if(last==null||now-last>30*60*1000L){due.add(Pair(t,d));lastAlert[key]=now}
   }
   if(due.isNotEmpty()){
    due.sortBy{it.second}
    val first=due[0]
    val br=Geo.bearing(l.latitude,l.longitude,first.first.lat,first.first.lon)
    val body=due.take(3).joinToString(" • "){"${it.first.name} (${Geo.fmtDist(it.second)})"}+(if(due.size>3)" +${due.size-3}" else "")
    val nm=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify((now%100000).toInt()+1000,build("dz_near","📍 قريب من ${first.first.kind}: ${first.first.name}","$body • الاتجاه ${br.toInt()}° ${Geo.cardinal(br)}",false))
   }
  }
 }
}
