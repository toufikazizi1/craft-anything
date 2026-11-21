package com.meteoriteatlas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Waypoints, ruler path, navigation arrow to a target, and a centre crosshair. */
class RouteOverlay(
 ctx:Context,
 private val onWp:(Waypoint)->Unit,
 private val onLong:(GeoPoint)->Unit,
 private val onRulerPoint:(GeoPoint)->Unit
):Overlay() {
 private val d=ctx.resources.displayMetrics.density
 var wps:List<Waypoint> = emptyList()
 var ruler:List<GeoPoint> = emptyList()
 var rulerMode=false
 var navTarget:GeoPoint?=null
 var myPos:GeoPoint?=null
 var recTrack:List<GeoPoint> = emptyList()
 var savedTracks:List<List<GeoPoint>> = emptyList()
 private val tpath=Path()
 private fun drawTrack(c:Canvas,pts:List<GeoPoint>,proj:org.osmdroid.views.Projection,color:Int){
  if(pts.size<2)return
  tpath.reset()
  val step=maxOf(1,pts.size/2500)
  val pt=Point()
  var i=0
  while(i<pts.size){
   proj.toPixels(pts[i],pt)
   if(i==0)tpath.moveTo(pt.x.toFloat(),pt.y.toFloat()) else tpath.lineTo(pt.x.toFloat(),pt.y.toFloat())
   i+=step
  }
  proj.toPixels(pts[pts.size-1],pt);tpath.lineTo(pt.x.toFloat(),pt.y.toFloat())
  line.pathEffect=null
  line.color=Color.BLACK;line.strokeWidth=7f*d;c.drawPath(tpath,line)
  line.color=color;line.strokeWidth=4f*d;c.drawPath(tpath,line)
 }

 private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND}
 private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
 private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=Color.WHITE}
 private val txt=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textAlign=Paint.Align.CENTER;isFakeBoldText=true}
 private val halo=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textAlign=Paint.Align.CENTER;style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND;isFakeBoldText=true}
 private val emoji=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER}
 private val path=Path()
 private val wpHits=ArrayList<Triple<Float,Float,Waypoint>>()

 private fun px(p:GeoPoint,proj:org.osmdroid.views.Projection):FloatArray{val pt=Point();proj.toPixels(p,pt);return floatArrayOf(pt.x.toFloat(),pt.y.toFloat())}

 private fun label(c:Canvas,t:String,x:Float,y:Float,size:Float=12f){
  halo.textSize=size*d;halo.strokeWidth=4f*d;txt.textSize=size*d
  c.drawText(t,x,y,halo);c.drawText(t,x,y,txt)
 }
 private fun arrowHead(c:Canvas,x:Float,y:Float,ang:Double,color:Int,size:Float){
  path.reset();path.moveTo(x,y)
  path.lineTo((x-size*cos(ang-0.45)).toFloat(),(y-size*sin(ang-0.45)).toFloat())
  path.lineTo((x-size*cos(ang+0.45)).toFloat(),(y-size*sin(ang+0.45)).toFloat())
  path.close()
  fill.color=Color.BLACK;fill.style=Paint.Style.STROKE;fill.strokeWidth=3f*d;c.drawPath(path,fill)
  fill.style=Paint.Style.FILL;fill.color=color;c.drawPath(path,fill)
 }
 private fun segment(c:Canvas,a:FloatArray,b:FloatArray,color:Int,dashed:Boolean,text:String?,arrowEnd:Boolean=true){
  line.color=Color.BLACK;line.strokeWidth=7f*d;line.pathEffect=null
  c.drawLine(a[0],a[1],b[0],b[1],line)
  line.color=color;line.strokeWidth=4f*d
  line.pathEffect=if(dashed)DashPathEffect(floatArrayOf(22f*d,12f*d),0f) else null
  c.drawLine(a[0],a[1],b[0],b[1],line)
  line.pathEffect=null
  val ang=atan2((b[1]-a[1]).toDouble(),(b[0]-a[0]).toDouble())
  val mx=(a[0]+b[0])/2;val my=(a[1]+b[1])/2
  if(arrowEnd)arrowHead(c,b[0],b[1],ang,color,16*d)
  if(text!=null)label(c,text,mx,my-12*d,12f)
 }
 private fun badge(c:Canvas,x:Float,y:Float,w:Waypoint){
  val col=WpStore.COLORS[w.color.coerceIn(0,WpStore.COLORS.size-1)]
  if(w.icon==0){
   val r=13*d;val cy=y-2.1f*r
   path.reset();path.moveTo(x,y);path.lineTo(x-0.88f*r,cy+0.5f*r);path.lineTo(x+0.88f*r,cy+0.5f*r);path.close()
   fill.style=Paint.Style.FILL;fill.color=col;c.drawPath(path,fill);c.drawCircle(x,cy,r,fill)
   edge.strokeWidth=2f*d;c.drawCircle(x,cy,r,edge)
   fill.color=Color.WHITE;c.drawCircle(x,cy,r*0.38f,fill)
   wpHits.add(Triple(x,cy,w))
  }else{
   val r=17*d;val cy=y-r-7*d
   path.reset();path.moveTo(x,y);path.lineTo(x-7*d,cy+r*0.7f);path.lineTo(x+7*d,cy+r*0.7f);path.close()
   fill.style=Paint.Style.FILL;fill.color=col;c.drawPath(path,fill);c.drawCircle(x,cy,r,fill)
   edge.strokeWidth=2.5f*d;c.drawCircle(x,cy,r,edge)
   emoji.textSize=19*d
   c.drawText(WpStore.ICONS[w.icon.coerceIn(0,WpStore.ICONS.size-1)],x,cy+6.5f*d,emoji)
   wpHits.add(Triple(x,cy,w))
  }
 }

 override fun draw(c:Canvas,osmv:MapView,shadow:Boolean){
  if(shadow)return
  val proj=osmv.projection
  wpHits.clear()
  // navigation arrow
  val t=navTarget;val me=myPos
  if(t!=null&&me!=null){
   val a=px(me,proj);val b=px(t,proj)
   segment(c,a,b,Color.rgb(0,229,255),true,Geo.fmtDist(Geo.dist(me.latitude,me.longitude,t.latitude,t.longitude)),true)
  }
  if(t!=null){val b=px(t,proj);fill.style=Paint.Style.STROKE;fill.strokeWidth=4f*d;fill.color=Color.rgb(0,229,255);c.drawCircle(b[0],b[1],14*d,fill);fill.style=Paint.Style.FILL}
  // tracks
  for(tr in savedTracks)drawTrack(c,tr,proj,Color.rgb(171,71,188))
  drawTrack(c,recTrack,proj,Color.rgb(255,64,129))
  // ruler: fixed vertices + one live arrow from the last vertex to the screen centre
  val rl=ruler
  for(i in 1 until rl.size){
   val p=rl[i-1];val q=rl[i]
   segment(c,px(p,proj),px(q,proj),Color.rgb(255,235,59),false,Geo.fmtDist(Geo.dist(p.latitude,p.longitude,q.latitude,q.longitude)),false)
  }
  if(rulerMode&&rl.isNotEmpty()){
   val last=rl[rl.size-1]
   val cen=osmv.mapCenter
   val dm=Geo.dist(last.latitude,last.longitude,cen.latitude,cen.longitude)
   val br=Geo.bearing(last.latitude,last.longitude,cen.latitude,cen.longitude)
   if(dm>1.0)segment(c,px(last,proj),floatArrayOf(osmv.width/2f,osmv.height/2f),Color.rgb(255,152,0),false,"${Geo.fmtDist(dm)} • ${br.toInt()}° ${Geo.cardinal(br)}",true)
  }
  for(i in rl.indices){
   val p=px(rl[i],proj)
   fill.style=Paint.Style.FILL;fill.color=Color.rgb(255,235,59);c.drawCircle(p[0],p[1],10*d,fill)
   edge.strokeWidth=2f*d;c.drawCircle(p[0],p[1],10*d,edge)
   val old=txt.color;txt.textSize=11*d;txt.color=Color.BLACK;c.drawText(if(i==0)"A" else ('A'+i).toString(),p[0],p[1]+4*d,txt);txt.color=old
  }
  // waypoints
  for(w in wps){
   val p=px(GeoPoint(w.lat,w.lon),proj)
   if(p[0]< -100||p[1]< -100||p[0]>osmv.width+100||p[1]>osmv.height+150)continue
   badge(c,p[0],p[1],w)
   label(c,w.name,p[0],p[1]+15*d,12f)
  }
  // crosshair at centre
  val cx=osmv.width/2f;val cy=osmv.height/2f
  line.color=Color.BLACK;line.strokeWidth=4.5f*d;c.drawLine(cx-12*d,cy,cx+12*d,cy,line);c.drawLine(cx,cy-12*d,cx,cy+12*d,line)
  line.color=Color.WHITE;line.strokeWidth=2f*d;c.drawLine(cx-12*d,cy,cx+12*d,cy,line);c.drawLine(cx,cy-12*d,cx,cy+12*d,line)
 }

 override fun onSingleTapConfirmed(e:MotionEvent,mapView:MapView):Boolean{
  val tol=26*d
  var best:Waypoint?=null;var bd=Float.MAX_VALUE
  for(h in wpHits){val dx=h.first-e.x;val dy=h.second-e.y;val dd=dx*dx+dy*dy;if(dd<=tol*tol&&dd<bd){bd=dd;best=h.third}}
  if(rulerMode){
   val w0=best
   if(w0!=null){onRulerPoint(GeoPoint(w0.lat,w0.lon));return true}
   return false
  }
  val w=best?:return false
  onWp(w);return true
 }
 override fun onLongPress(e:MotionEvent,mapView:MapView):Boolean{
  val g=mapView.projection.fromPixels(e.x.toInt(),e.y.toInt())
  onLong(GeoPoint(g.latitude,g.longitude));return true
 }
}
