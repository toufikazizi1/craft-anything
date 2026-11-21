package com.meteoriteatlas

import org.json.JSONArray
import org.json.JSONObject
import android.content.SharedPreferences
import android.graphics.Color
import java.util.Locale
import kotlin.math.*

object Geo {
 private fun rad(x:Double)=Math.toRadians(x)
 /** meters */
 fun dist(lat1:Double,lon1:Double,lat2:Double,lon2:Double):Double{
  val r=6371008.8
  val p1=rad(lat1);val p2=rad(lat2);val dp=p2-p1;val dl=rad(lon2-lon1)
  val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
  return 2*r*asin(min(1.0,sqrt(h)))
 }
 fun bearing(lat1:Double,lon1:Double,lat2:Double,lon2:Double):Double{
  val p1=rad(lat1);val p2=rad(lat2);val dl=rad(lon2-lon1)
  val y=sin(dl)*cos(p2);val x=cos(p1)*sin(p2)-sin(p1)*cos(p2)*cos(dl)
  return (Math.toDegrees(atan2(y,x))+360.0)%360.0
 }
 fun cardinal(deg:Double):String{
  val n=listOf("شمال","شمال شرق","شرق","جنوب شرق","جنوب","جنوب غرب","غرب","شمال غرب")
  return n[(((deg+22.5)/45.0).toInt())%8]
 }
 fun fmtDist(m:Double):String=if(m<1000.0)"${m.roundToInt()} م" else String.format(Locale.US,"%.2f كم",m/1000.0)
 fun dec(lat:Double,lon:Double):String=String.format(Locale.US,"%.6f° %s   %.6f° %s",abs(lat),if(lat>=0)"N" else "S",abs(lon),if(lon>=0)"E" else "W")
 private fun dms1(v:Double,pos:String,neg:String):String{
  val a=abs(v);val d=a.toInt();val mf=(a-d)*60.0;val m=mf.toInt();val s=(mf-m)*60.0
  return String.format(Locale.US,"%d°%02d'%04.1f\"%s",d,m,s,if(v>=0)pos else neg)
 }
 fun dms(lat:Double,lon:Double):String=dms1(lat,"N","S")+"   "+dms1(lon,"E","W")
 fun full(lat:Double,lon:Double):String=dec(lat,lon)+"\n"+dms(lat,lon)
}

data class Waypoint(val id:Long,var name:String,var note:String,var lat:Double,var lon:Double,var icon:Int,var color:Int)

object WpStore {
 val ICONS=listOf("📍","🚩","⭐","⛺","🚗","💧","⚠️","☄️","⛏️","🏠","❌","🧭")
 val ICON_NAMES=listOf("دبوس","علم","نجمة","مخيم","سيارة","ماء","خطر","نيزك","حفر/تنقيب","بيت","علامة X","بوصلة")
 val COLORS=intArrayOf(Color.rgb(229,57,53),Color.rgb(30,136,229),Color.rgb(67,160,71),Color.rgb(253,216,53),Color.rgb(142,36,170),Color.rgb(33,33,33),Color.rgb(251,140,0))
 val COLOR_NAMES=listOf("أحمر","أزرق","أخضر","أصفر","بنفسجي","أسود","برتقالي")
 fun load(p:SharedPreferences):MutableList<Waypoint>{
  val out=ArrayList<Waypoint>()
  try{
   val a=JSONArray(p.getString("waypoints","[]"))
   for(i in 0 until a.length()){val o=a.getJSONObject(i);out.add(Waypoint(o.getLong("id"),o.optString("name"),o.optString("note"),o.getDouble("lat"),o.getDouble("lon"),o.optInt("icon",0),o.optInt("color",0)))}
  }catch(_:Exception){}
  return out
 }
 fun save(p:SharedPreferences,list:List<Waypoint>){
  val a=JSONArray()
  for(w in list){a.put(JSONObject().put("id",w.id).put("name",w.name).put("note",w.note).put("lat",w.lat).put("lon",w.lon).put("icon",w.icon).put("color",w.color))}
  p.edit().putString("waypoints",a.toString()).apply()
 }
}
