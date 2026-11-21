package com.meteoriteatlas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

data class Place(val lat:Double,val lon:Double,val kind:String,val name:String,val alt:String)

/** Offline labels for villages, hamlets, wells and springs (downloaded once from OpenStreetMap). */
class PlacesOverlay(ctx:Context,private val onPlace:(Place)->Unit):Overlay() {
 private val d=ctx.resources.displayMetrics.density
 var items:List<Place> = emptyList()
 private val hits=ArrayList<Triple<Float,Float,Place>>()
 private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
 private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=Color.WHITE}
 private val txt=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER;isFakeBoldText=true}
 private val halo=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textAlign=Paint.Align.CENTER;style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND;isFakeBoldText=true}
 companion object{
  fun rank(k:String):Int=when(k){"town"->0;"village"->1;"water","well","spring"->2;else->3}
  fun minZoom(k:String):Double=when(k){"town"->7.0;"village"->9.0;"water","well","spring"->9.5;else->10.5}
  fun kindAr(k:String):String=when(k){"town"->"بلدة";"village"->"قرية";"hamlet"->"مزرعة/عزبة";"locality"->"موقع/منطقة";"isolated_dwelling"->"مسكن معزول";"well"->"بئر";"spring"->"عين";"water"->"نقطة ماء";else->k}
 }
 fun setData(l:List<Place>){items=l.sortedBy{rank(it.kind)}}
 private fun isWater(k:String)=k=="water"||k=="well"||k=="spring"

 override fun draw(c:Canvas,osmv:MapView,shadow:Boolean){
  if(shadow)return
  hits.clear()
  val z=osmv.zoomLevelDouble
  if(z<7.0||items.isEmpty())return
  val bb=osmv.boundingBox
  val m=0.05
  val proj=osmv.projection
  val pt=Point()
  val gp=GeoPoint(0.0,0.0)
  val occ=HashSet<Long>()
  halo.textSize=11*d;halo.strokeWidth=3.5f*d;txt.textSize=11*d
  val w=osmv.width.toFloat();val h=osmv.height.toFloat()
  for(p in items){
   if(z<minZoom(p.kind))continue
   if(p.lat>bb.latNorth+m||p.lat<bb.latSouth-m||p.lon>bb.lonEast+m||p.lon<bb.lonWest-m)continue
   gp.setCoords(p.lat,p.lon);proj.toPixels(gp,pt)
   val x=pt.x.toFloat();val y=pt.y.toFloat()
   if(x<0||y<0||x>w||y>h)continue
   val key=(((x/(95*d)).toLong()) shl 32) xor (((y/(20*d)).toLong()) and 0xffffffffL)
   if(!occ.add(key))continue
   val water=isWater(p.kind)
   fill.color=if(water)Color.rgb(41,182,246) else Color.WHITE
   c.drawCircle(x,y,4.5f*d,fill)
   edge.color=Color.BLACK;edge.strokeWidth=1.3f*d;c.drawCircle(x,y,4.5f*d,edge)
   txt.color=if(water)Color.rgb(179,229,252) else Color.WHITE
   val label=(if(water)"💧 " else "")+p.name
   c.drawText(label,x,y+15*d,halo);c.drawText(label,x,y+15*d,txt)
   hits.add(Triple(x,y,p))
  }
 }
 override fun onSingleTapConfirmed(e:MotionEvent,mapView:MapView):Boolean{
  val tol=24*d;var best:Place?=null;var bd=Float.MAX_VALUE
  for(h in hits){val dx=h.first-e.x;val dy=h.second-e.y;val dd=dx*dx+dy*dy;if(dd<=tol*tol&&dd<bd){bd=dd;best=h.third}}
  val b=best?:return false
  onPlace(b);return true
 }
}
