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
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Draws meteorites as big clustered dots (visible at every zoom) with name labels when zoomed in, plus fireball events. */
class PointsOverlay(
 ctx:Context,
 private val onMet:(Meteorite)->Unit,
 private val onGroup:(List<Meteorite>,GeoPoint)->Unit,
 private val onFire:(FireballEvent)->Unit
):Overlay() {
 private val d=ctx.resources.displayMetrics.density
 var mets:List<Meteorite> = emptyList()
 var fires:List<FireballEvent> = emptyList()

 private class Hit(val x:Float,val y:Float,val r:Float,val list:List<Meteorite>?,val fire:FireballEvent?)
 private class Cl {
  var sx=0f;var sy=0f;val list=ArrayList<Meteorite>()
  fun add(m:Meteorite,x:Float,y:Float){sx+=x;sy+=y;list.add(m)}
 }
 private val hits=ArrayList<Hit>()
 private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
 private val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE}
 private val txt=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textAlign=Paint.Align.CENTER;isFakeBoldText=true}
 private val halo=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textAlign=Paint.Align.CENTER;style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND;isFakeBoldText=true}

 private fun isFell(m:Meteorite):Boolean{val f=m.fall.lowercase();return f=="fell"||f=="fall"||f.startsWith("y")||f.contains("fell")}

 override fun draw(c:Canvas,osmv:MapView,shadow:Boolean){
  if(shadow)return
  hits.clear()
  val proj=osmv.projection
  val z=osmv.zoomLevelDouble
  val w=osmv.width.toFloat();val h=osmv.height.toFloat()
  val margin=60*d
  val cell=if(z>=13.0)26*d else 50*d
  val pt=Point()
  val groups=HashMap<Long,Cl>()
  for(mt in mets){
   proj.toPixels(GeoPoint(mt.lat,mt.lon),pt)
   val x=pt.x.toFloat();val y=pt.y.toFloat()
   if(x< -margin||y< -margin||x>w+margin||y>h+margin)continue
   val key=(floor(x/cell).toLong() shl 32) xor (floor(y/cell).toLong() and 0xffffffffL)
   groups.getOrPut(key){Cl()}.add(mt,x,y)
  }
  txt.textSize=13*d
  halo.textSize=12*d;halo.strokeWidth=3.5f*d
  for(cl in groups.values){
   val n=cl.list.size
   val x=cl.sx/n;val y=cl.sy/n
   if(n==1){
    val m=cl.list[0]
    val r=9*d
    fill.color=if(isFell(m))Color.rgb(229,57,53) else Color.rgb(255,152,0)
    c.drawCircle(x,y,r+1.5f*d,halo.apply{style=Paint.Style.FILL;color=Color.BLACK;alpha=160})
    halo.style=Paint.Style.STROKE;halo.color=Color.BLACK;halo.alpha=255
    c.drawCircle(x,y,r,fill)
    ring.color=Color.WHITE;ring.strokeWidth=2.5f*d
    c.drawCircle(x,y,r,ring)
    if(z>=6.5){
     val label=m.name
     val ty=y-r-5*d
     c.drawText(label,x,ty,halo)
     c.drawText(label,x,ty,txt.apply{textSize=12*d})
     txt.textSize=13*d
    }
    hits.add(Hit(x,y,maxOf(r,20*d),cl.list,null))
   }else{
    val r=(15+min(12f,sqrt(n.toFloat())*2.2f))*d
    fill.color=Color.argb(235,211,47,47)
    c.drawCircle(x,y,r,fill)
    ring.color=Color.WHITE;ring.strokeWidth=3f*d
    c.drawCircle(x,y,r,ring)
    c.drawText(n.toString(),x,y+txt.textSize*0.35f,txt)
    hits.add(Hit(x,y,maxOf(r,22*d),cl.list,null))
   }
  }
  for(ev in fires){
   proj.toPixels(GeoPoint(ev.latitude,ev.longitude),pt)
   val x=pt.x.toFloat();val y=pt.y.toFloat()
   if(x< -margin||y< -margin||x>w+margin||y>h+margin)continue
   val r=9*d
   fill.color=Color.rgb(255,214,0)
   c.drawCircle(x,y,r,fill)
   ring.color=Color.rgb(255,87,34);ring.strokeWidth=3f*d
   c.drawCircle(x,y,r,ring)
   hits.add(Hit(x,y,maxOf(r,20*d),null,ev))
  }
 }

 override fun onSingleTapConfirmed(e:MotionEvent,mapView:MapView):Boolean{
  var best:Hit?=null;var bd=Float.MAX_VALUE
  for(hh in hits){
   val dx=hh.x-e.x;val dy=hh.y-e.y;val dd=dx*dx+dy*dy
   if(dd<=hh.r*hh.r&&dd<bd){bd=dd;best=hh}
  }
  val b=best?:return false
  val f=b.fire
  if(f!=null){onFire(f);return true}
  val l=b.list?:return false
  if(l.size==1)onMet(l[0]) else onGroup(l,GeoPoint(l.map{it.lat}.average(),l.map{it.lon}.average()))
  return true
 }
}
