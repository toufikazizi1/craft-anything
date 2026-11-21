package com.meteoriteatlas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** Draws every meteorite as a red pin (always visible at any zoom), name labels when zoomed in, plus fireball events. */
class PointsOverlay(
 ctx:Context,
 private val onMet:(Meteorite)->Unit,
 private val onGroup:(List<Meteorite>,GeoPoint)->Unit,
 private val onFire:(FireballEvent)->Unit
):Overlay() {
 private val d=ctx.resources.displayMetrics.density
 var mets:List<Meteorite> = emptyList()
 var fires:List<FireballEvent> = emptyList()

 private class Hit(val x:Float,val y:Float,val r:Float,val met:Meteorite?,val fire:FireballEvent?)
 private val hits=ArrayList<Hit>()
 private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
 private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=Color.WHITE}
 private val txt=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textAlign=Paint.Align.CENTER;isFakeBoldText=true}
 private val halo=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textAlign=Paint.Align.CENTER;style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND;isFakeBoldText=true}
 private val path=Path()

 private fun isFell(m:Meteorite):Boolean{val f=m.fall.lowercase();return f=="fell"||f=="fall"||f.startsWith("y")||f.contains("fell")}

 private fun pin(c:Canvas,x:Float,y:Float,r:Float,color:Int){
  val cy=y-2.1f*r
  path.reset()
  path.moveTo(x,y)
  path.lineTo(x-0.88f*r,cy+0.5f*r)
  path.lineTo(x+0.88f*r,cy+0.5f*r)
  path.close()
  fill.color=color
  c.drawPath(path,fill)
  c.drawCircle(x,cy,r,fill)
  edge.strokeWidth=1.6f*d
  c.drawCircle(x,cy,r,edge)
  fill.color=Color.WHITE
  c.drawCircle(x,cy,r*0.38f,fill)
 }

 override fun draw(c:Canvas,osmv:MapView,shadow:Boolean){
  if(shadow)return
  hits.clear()
  val proj=osmv.projection
  val z=osmv.zoomLevelDouble
  val w=osmv.width.toFloat();val h=osmv.height.toFloat()
  val margin=80*d
  val r=10*d
  val pt=Point()
  val gp=GeoPoint(0.0,0.0)
  halo.textSize=12*d;halo.strokeWidth=3.5f*d
  txt.textSize=12*d
  for(m in mets){
   gp.setCoords(m.lat,m.lon)
   proj.toPixels(gp,pt)
   val x=pt.x.toFloat();val y=pt.y.toFloat()
   if(x< -margin||y< -margin||x>w+margin||y>h+margin+3*r)continue
   pin(c,x,y,r,if(isFell(m))Color.rgb(229,57,53) else Color.rgb(244,81,30))
   hits.add(Hit(x,y-2.1f*r,r,m,null))
  }
  for(ev in fires){
   gp.setCoords(ev.latitude,ev.longitude)
   proj.toPixels(gp,pt)
   val x=pt.x.toFloat();val y=pt.y.toFloat()
   if(x< -margin||y< -margin||x>w+margin||y>h+margin)continue
   fill.color=Color.rgb(255,214,0)
   c.drawCircle(x,y,9*d,fill)
   edge.color=Color.rgb(255,87,34);edge.strokeWidth=3f*d
   c.drawCircle(x,y,9*d,edge)
   edge.color=Color.WHITE
   hits.add(Hit(x,y,9*d,null,ev))
  }
 }

 override fun onSingleTapConfirmed(e:MotionEvent,mapView:MapView):Boolean{
  val tol=24*d
  var best:Hit?=null;var bd=Float.MAX_VALUE
  for(hh in hits){
   val dx=hh.x-e.x;val dy=hh.y-e.y;val dd=dx*dx+dy*dy
   val lim=maxOf(hh.r,tol)
   if(dd<=lim*lim&&dd<bd){bd=dd;best=hh}
  }
  val b=best?:return false
  val f=b.fire
  if(f!=null){onFire(f);return true}
  val near=ArrayList<Meteorite>()
  for(hh in hits){
   val m=hh.met?:continue
   val dx=hh.x-b.x;val dy=hh.y-b.y
   if(dx*dx+dy*dy<=(12*d)*(12*d))near.add(m)
  }
  if(near.size>1)onGroup(near,GeoPoint(near.map{it.lat}.average(),near.map{it.lon}.average()))
  else b.met?.let(onMet)
  return true
 }
}
