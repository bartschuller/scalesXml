package scales.utils

import collection.{IndexedSeqOptimized, IndexedSeqLike, IndexedSeq}
import collection.mutable.Builder //{ArrayBuilder, Builder}
import collection.generic.{CanBuildFrom, GenericTraversableTemplate, SeqFactory, GenericCompanion}

//import scales.collection.immutable.Vector

object ImmutableArray {
  val emptyImmutableArray = new ImmutableArray[Nothing](Array[AnyRef](),0,0)
}

/**
 * Behaves like an ArrayList/ArrayBuffer, growing an internal array as necessary
 */
case class ImmutableArrayBuilder[ A ]() extends Builder[A, ImmutableArray[A]]{

  final val gf = 0.10
  final val gp = 0.95

  def resize( orig : Array[AnyRef], newCapacity : Int, len : Int ) = { 
    val ar = Array.ofDim[AnyRef](newCapacity)
    if (len != 0)
      Array.copy(orig, 0, ar, 0, len)

    ar
  }

  var buf : Array[AnyRef] = _
  
  var len = 0

  protected def ensureSize( size : Int ) {
    if ((buf eq null) || (size > buf.length))
      buf = resize( buf, size, len )
    else if (size > (buf.length * gp).toInt) {
      buf = resize( buf, buf.length + (buf.length.toDouble * gf).toInt, len )
    }
  }

  override def sizeHint( size : Int ) {
    if ((buf eq null) || size > buf.length) // don't grow unless necessary
      buf = resize( buf, size, len )
  }

  def result : ImmutableArray[A] = 
    if (len == 0)
      ImmutableArray.emptyImmutableArray.asInstanceOf[ImmutableArray[A]]
    else
      ImmutableArray(buf, 0, len)

  override def ++=(xs: TraversableOnce[A]): this.type = xs match {
    case ImmutableArray( base, offset, slen) =>
      ensureSize(len + slen)
      Array.copy(base, offset, buf, len, slen)
      len += slen
      this
    case _ =>
      super.++=(xs)
  }

  def +=( elem : A) : this.type = {
    ensureSize(len + 1)
    // we know its big enough
    buf(len) = elem.asInstanceOf[AnyRef]
    len += 1
    this
  }

  def clear() {
    len = 0
  }
}

case class IAEmpty[ +A ]() extends ImmutableArrayProxy[A] {
  def length = 0

  def apply(idx : Int) = error("Can't return an item, as we are empty")

  def ar = this  

  @inline override def :+[B >: A, That](elem: B)(implicit bf: CanBuildFrom[ImmutableArrayProxy[A], B, That]): That =
    IAOne(elem).asInstanceOf[That]

}

case class IAOne[ +A ]( one : A ) extends ImmutableArrayProxy[A] {

  def apply(idx : Int) = one

  def length = 1

  def ar = this

  @inline override def :+[B >: A, That](elem: B)(implicit bf: CanBuildFrom[ImmutableArrayProxy[A], B, That]): That =
    IATwo(one, elem).asInstanceOf[That]

  @inline override def updated[B >: A, That](index: Int, elem: B)(implicit bf: CanBuildFrom[ImmutableArrayProxy[A], B, That]): That =
    IAOne(elem).asInstanceOf[That]

}

import scala.annotation.switch

case class IATwo[ +A ]( one : A, two : A ) extends ImmutableArrayProxy[A] {

  def apply(idx : Int) = (idx : @switch) match {
    case 0 => one
    case 1 => two
  }

  def length = 2

  def ar = this

  @inline override def :+[B >: A, That](elem: B)(implicit bf: CanBuildFrom[ImmutableArrayProxy[A], B, That]): That =
    IAThree(one, two, elem).asInstanceOf[That]

  @inline override def updated[B >: A, That](index: Int, elem: B)(implicit bf: CanBuildFrom[ImmutableArrayProxy[A], B, That]): That =
     ((index : @switch) match {
       case 0 => IATwo(elem, two)
       case 1 => IATwo(one, elem)
     }).asInstanceOf[That]

}

case class IAThree[ +A ]( one : A, two : A, three : A ) extends ImmutableArrayProxy[A] {

  def apply(idx : Int) = (idx : @switch) match {
    case 0 => one
    case 1 => two
    case 2 => three
  }

  def length = 3

  def ar = this

  @inline override def updated[B >: A, That](index: Int, elem: B)(implicit bf: CanBuildFrom[ImmutableArrayProxy[A], B, That]): That =
     ((index : @switch) match {
       case 0 => IAThree(elem, two, three)
       case 1 => IAThree(one, elem, three)
       case 2 => IAThree(one, two, elem)
     }).asInstanceOf[That]

}


/**
 * Object arrays are just faster, System.arraycopy doesn't trust you and will type check everything, we can let nsc do that job for us.
 *
 * Same as ImmutableArray but for when the base is the entire collection, no offset or len are then needed
 */ 
trait ImmutableArrayT[ +A ] extends ImmutableArrayProxy[A] {
  val base : Array[AnyRef]
  def offset : Int
  def len : Int

  import ImmutableArrayProxyBuilder._

  def apply(idx : Int ) = base(idx + offset).asInstanceOf[A]
 
  def length = len
  
  @inline override def +:[B >: A, That](elem: B)(implicit bf: CanBuildFrom[ImmutableArrayProxy[A], B, That]): That =
    (if (len == vectorAfter) super.+:(elem)
    else {
      val ar = Array.ofDim[AnyRef](len+1)
      Array.copy(base, offset, ar, 1, len)
      ar(0) = elem.asInstanceOf[AnyRef]
      ImmutableArrayAll[B](ar)
    }).asInstanceOf[That]
  
  @inline override def :+[B >: A, That](elem: B)(implicit bf: CanBuildFrom[ImmutableArrayProxy[A], B, That]): That =
    (if (len == vectorAfter) super.:+(elem)
    else {
      val ar = Array.ofDim[AnyRef](len+1)
      Array.copy(base, offset, ar, 0, len)
      ar(len) = elem.asInstanceOf[AnyRef]
      ImmutableArrayAll[B](ar)
    }).asInstanceOf[That]

  @inline override def take( n : Int ) = 
    ImmutableArray(base, offset, if (len - n < 0) len else n)

  @inline override def drop( n : Int ) = 
    ImmutableArray(base, offset + n, if (len - n < 0) 0 else (len - n))

  @inline override def tail = drop(1)

  /**
   * we can do better - slice used by many functions in Optimized
   */ 
  @inline override def slice(from: Int, until: Int) = {
    val lo    = math.max(from, 0)
    val hi    = math.min(until, len)
    val elems = math.max(hi - lo, 0)
    ImmutableArray(base, offset, elems)
  }

  /**
   * Basically optimised version for back, hint used directly, one new array creation
   */ 
  override def updated[B >: A, That](index: Int, elem: B)(implicit bf: CanBuildFrom[ImmutableArrayProxy[A], B, That]): That =
    if (bf.isInstanceOf[ImmutableArrayProxy.ImmutableArrayProxyCBF[_]]) {
      // we know its objects underneath, we know the relationship is sound
      val ar = Array.ofDim[AnyRef](len)
      Array.copy(base, offset, ar, 0, len)
      ar(index) = elem.asInstanceOf[AnyRef]
      ImmutableArrayAll[B](ar).asInstanceOf[That]
    } else {
      val b = bf(repr)
      val (prefix, rest) = this.splitAt(index)
      b.sizeHint(len)
      b ++= toCollection(prefix)
      b += elem
      b ++= toCollection(rest.tail)
      b.result()
    }

  override def toArray[U >: A : ArrayTag]: Array[U] =
    if (implicitly[ClassTag[U]].erasure eq base.getClass.getComponentType) {
      if ((offset == 0) && (len == base.length))
	base.asInstanceOf[Array[U]]
      else {
	val ar = Array.ofDim[U](len)
	Array.copy(base, offset, ar, 0, len)
	ar	
      }
    } else 
      super.toArray[U]


  def ar = this
}

/**
 * Don't add the offset and length, for building a dom this save 8 per elem, only matters for large docs (can save 4mb from 54mb), but can't hurt small ones.
 */ 
case class ImmutableArrayAll[ +A ]( base : Array[AnyRef]) extends ImmutableArrayT[A] {
  @inline final def offset = 0
  @inline final def len = base.length
  @inline final override def length = base.length
}

/**
 * Object arrays are just faster, System.arraycopy doesn't trust you and will type check everything, we can let nsc do that job for us.
 */ 
case class ImmutableArray[ +A ]( base : Array[AnyRef], offset : Int, len : Int) extends ImmutableArrayT[A]

/**
 * Starts an ImmutableArrayProxy and provides the CanBuildFrom
 */ 
object ImmutableArrayProxy extends SeqFactory[ImmutableArrayProxy] {
  val emptyImmutableArray = IAEmpty[Nothing]()
    //new ImmutableArray[Nothing](Array[AnyRef](),0,0)

  @inline final override def empty[ A ] : ImmutableArrayProxy[A]  = emptyImmutableArray.asInstanceOf[ImmutableArrayProxy[A]]

  @inline def newBuilder[A]
  : Builder[A, ImmutableArrayProxy[A]] =
    ImmutableArrayProxyBuilder()
  
  @inline implicit def canBuildFrom[T](implicit ma: ClassManifest[T]): CanBuildFrom[ImmutableArrayProxy[_], T, ImmutableArrayProxy[T]] = new ImmutableArrayProxyCBF[T]{ val m = ma }

  trait ImmutableArrayProxyCBF[T] extends CanBuildFrom[ImmutableArrayProxy[_], T, ImmutableArrayProxy[T]] {
    
    val m : ClassManifest[T]

    def apply(from: ImmutableArrayProxy[_]): Builder[T, ImmutableArrayProxy[T]] = newBuilder
    def apply: Builder[T, ImmutableArrayProxy[T]] = newBuilder
  }
    
}

object ImmutableArrayProxyBuilder {
  final val vectorAfter = 31
}

/**
 * An attempt to be a little more efficient with ImmutableArray, should make up for update not working?
 */
case class ImmutableArrayProxyBuilder[ A ]() extends Builder[A, ImmutableArrayProxy[A]]{
  import ImmutableArrayProxyBuilder._

  lazy val arrayBuilder = new ImmutableArrayBuilder[A]()
  lazy val vectorBuilder = Vector.newBuilder[A]
  
  var inVector = false
  var haveChosen = false

  override def sizeHint( size : Int ) {
    if (size > vectorAfter) {
      inVector = true
      haveChosen = true
    }

    if (inVector) {
      vectorBuilder.sizeHint(size)
    } else {
      arrayBuilder.sizeHint(size)
    }
  }

  // for the case when we were under 32 but are now over
  protected def checkVB() {
    if (!inVector) {
      if (arrayBuilder.len > vectorAfter) {
	moveToVector
      }
    }
  }

  protected def moveToVector() {
    // copy over
    val r = arrayBuilder.result
    vectorBuilder.sizeHint(r.len)
    vectorBuilder.++=(r)
    arrayBuilder.clear
    inVector = true
    haveChosen = true
  }

  def result : ImmutableArrayProxy[A] =
    if (inVector) VectorImpl(vectorBuilder.result)
    else { // do it here as this is the correct type
      import arrayBuilder.{buf, len}
      import scala.annotation.switch	

      (len : @switch) match {
	case 0 =>
	  ImmutableArrayProxy.emptyImmutableArray.asInstanceOf[ImmutableArrayProxy[A]]
	case 1 => //TODO this is way too much like optimisation strategy..
	  IAOne(buf(0).asInstanceOf[A])
	case 2 =>
	  IATwo(buf(0).asInstanceOf[A], buf(1).asInstanceOf[A])
	case 3 =>
	  IAThree(buf(0).asInstanceOf[A], buf(1).asInstanceOf[A], buf(2).asInstanceOf[A])
	case _ => 
	  if (len != 0 && len == buf.length)
	    ImmutableArrayAll[A](buf)
	  else
	    arrayBuilder.result
      }
    }

  override def ++=(xs: TraversableOnce[A]): this.type = {
    // if its already a vector don't start with arrays again
    if (!haveChosen && xs.isInstanceOf[VectorImpl[A]]) {
      inVector = true
      haveChosen = true
    }
    
    xs match {
      case p : ImmutableArrayProxy[A] => 
	if (inVector) 
	  vectorBuilder.++=(p.ar) 
	else
	  arrayBuilder.++=(p.ar)
      case _ => 
	if (inVector)
	  vectorBuilder.++=(xs)
	else
	  arrayBuilder.++=(xs)
    }      

    checkVB
    this
  }

  def +=( elem : A) : this.type =
    if (inVector) {
      vectorBuilder.+=(elem)
      this
    } else {
      arrayBuilder.+=(elem)
      checkVB
      this
    }

  def clear() {
    vectorBuilder.clear
    arrayBuilder.clear
    inVector = false
    haveChosen = false
  }
}

/**
 * Wraps behaviour of ImmutableArray like objects, when the array is greater than 31 it will be swapped to Vector.
 *
 */ 
trait ImmutableArrayProxy[+A] extends IndexedSeq[A] with IndexedSeqOptimized[A, ImmutableArrayProxy[A]] with GenericTraversableTemplate[A, ImmutableArrayProxy] {

  @inline override def companion: GenericCompanion[ImmutableArrayProxy] = ImmutableArrayProxy

  override protected[this] def newBuilder: Builder[A, ImmutableArrayProxy[A]] = ImmutableArrayProxy.newBuilder[A]

  def ar : TraversableOnce[A]
 
}

/**
 * Proxy Vector.  When its in Vector it stays in Vector. 
 */ 
case class VectorImpl[ +A ](ar : Vector[A]) extends ImmutableArrayProxy[A] {

  def apply(idx : Int ) = ar.apply(idx)
 
  def length = ar.length
  
  @inline override def +:[B >: A, That](elem: B)(implicit bf: CanBuildFrom[ImmutableArrayProxy[A], B, That]): That = VectorImpl(ar.+:(elem)).asInstanceOf[That]
  
  @inline override def :+[B >: A, That](elem: B)(implicit bf: CanBuildFrom[ImmutableArrayProxy[A], B, That]): That = VectorImpl(ar.:+(elem)).asInstanceOf[That]

  @inline override def take( n : Int ) = VectorImpl(ar.take(n))

  @inline override def drop( n : Int ) = VectorImpl(ar.drop(n))

  @inline override def tail = VectorImpl(ar.tail)

  @inline override def slice(from: Int, until: Int) = VectorImpl(ar.slice(from, until))

  override def updated[B >: A, That](index: Int, elem: B)(implicit bf: CanBuildFrom[ImmutableArrayProxy[A], B, That]): That = VectorImpl(ar.updated(index, elem)).asInstanceOf[That]

  override def toArray[U >: A : ArrayTag]: Array[U] = ar.toArray

}

