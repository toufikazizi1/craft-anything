package com.meteoriteatlas

import android.util.Xml
import java.io.File
import java.io.StringReader
import java.util.Locale
import kotlin.math.abs

data class TrackPt(val t:Long,val lat:Double,val lon:Double,val alt:Double)

object Gpx {
 private fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
 fun build(wps:List<Waypoint>,tracks:List<Pair<String,List<TrackPt>>>):String{
  val sb=StringBuilder()
  sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Meteorite Atlas\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
  for(w in wps){
   sb.append(String.format(Locale.US," <wpt lat=\"%.7f\" lon=\"%.7f\"><name>%s</name><desc>%s</desc><sym>%s</sym></wpt>\n",w.lat,w.lon,esc(w.name),esc(w.note),esc(WpStore.ICON_NAMES[w.icon.coerceIn(0,WpStore.ICON_NAMES.size-1)])))
  }
  for((name,pts) in tracks){
   sb.append(" <trk><name>").append(esc(name)).append("</name><trkseg>\n")
   for(p in pts){
    sb.append(String.format(Locale.US,"  <trkpt lat=\"%.7f\" lon=\"%.7f\">",p.lat,p.lon))
    if(p.alt!=0.0)sb.append(String.format(Locale.US,"<ele>%.1f</ele>",p.alt))
    if(p.t>0)sb.append("<time>").append(java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US).apply{timeZone=java.util.TimeZone.getTimeZone("UTC")}.format(java.util.Date(p.t))).append("</time>")
    sb.append("</trkpt>\n")
   }
   sb.append(" </trkseg></trk>\n")
  }
  sb.append("</gpx>\n")
  return sb.toString()
 }
 fun parse(text:String):Pair<List<Waypoint>,List<Pair<String,List<TrackPt>>>>{
  val wps=ArrayList<Waypoint>();val trks=ArrayList<Pair<String,List<TrackPt>>>()
  val p=Xml.newPullParser();p.setInput(StringReader(text))
  var ev=p.eventType
  var inWpt=false;var inTrk=false
  var wLat=0.0;var wLon=0.0;var wName="";var wDesc=""
  var tName="";var cur=ArrayList<TrackPt>()
  var pLat=0.0;var pLon=0.0;var pAlt=0.0;var inPt=false
  var tag=""
  while(ev!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){
   when(ev){
    org.xmlpull.v1.XmlPullParser.START_TAG->{
     tag=p.name
     when(tag){
      "wpt"->{inWpt=true;wLat=p.getAttributeValue(null,"lat")?.toDoubleOrNull()?:0.0;wLon=p.getAttributeValue(null,"lon")?.toDoubleOrNull()?:0.0;wName="";wDesc=""}
      "trk"->{inTrk=true;tName="";cur=ArrayList()}
      "trkpt"->{inPt=true;pLat=p.getAttributeValue(null,"lat")?.toDoubleOrNull()?:0.0;pLon=p.getAttributeValue(null,"lon")?.toDoubleOrNull()?:0.0;pAlt=0.0}
     }
    }
    org.xmlpull.v1.XmlPullParser.TEXT->{
     val t=p.text?.trim()?:""
     if(t.isNotEmpty()){
      if(inWpt&&tag=="name")wName=t
      else if(inWpt&&(tag=="desc"||tag=="cmt"))wDesc=t
      else if(inPt&&tag=="ele")pAlt=t.toDoubleOrNull()?:0.0
      else if(inTrk&&!inPt&&tag=="name")tName=t
     }
    }
    org.xmlpull.v1.XmlPullParser.END_TAG->{
     when(p.name){
      "wpt"->{inWpt=false;if(abs(wLat)>0||abs(wLon)>0)wps.add(Waypoint(System.nanoTime(),wName.ifBlank{"علامة"},wDesc,wLat,wLon,0,0))}
      "trkpt"->{inPt=false;cur.add(TrackPt(0L,pLat,pLon,pAlt))}
      "trk"->{inTrk=false;if(cur.size>=2)trks.add(Pair(tName.ifBlank{"مسار"},cur))}
     }
     tag=""
    }
   }
   ev=p.next()
  }
  return Pair(wps,trks)
 }
 fun readCsv(f:File):List<TrackPt>{
  val out=ArrayList<TrackPt>()
  try{f.useLines(Charsets.UTF_8){ls->for(ln in ls){val c=ln.split(',');if(c.size>=3){val la=c[1].toDoubleOrNull();val lo=c[2].toDoubleOrNull();if(la!=null&&lo!=null)out.add(TrackPt(c[0].toLongOrNull()?:0L,la,lo,c.getOrNull(3)?.toDoubleOrNull()?:0.0))}}}}catch(_:Exception){}
  return out
 }
 fun writeCsv(f:File,pts:List<TrackPt>){
  val sb=StringBuilder();for(p in pts)sb.append(p.t).append(',').append(p.lat).append(',').append(p.lon).append(',').append(p.alt).append('\n')
  f.writeText(sb.toString(),Charsets.UTF_8)
 }
 fun length(pts:List<TrackPt>):Double{var d=0.0;for(i in 1 until pts.size)d+=Geo.dist(pts[i-1].lat,pts[i-1].lon,pts[i].lat,pts[i].lon);return d}
 fun ascent(pts:List<TrackPt>):Double{
  var up=0.0;var last:Double?=null
  for(p in pts){if(p.alt==0.0)continue;val l=last;if(l==null)last=p.alt else if(abs(p.alt-l)>=3.0){if(p.alt>l)up+=p.alt-l;last=p.alt}}
  return up
 }
}
