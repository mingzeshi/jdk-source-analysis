/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import sun.misc.Unsafe;

/**
 * Provides a framework for implementing blocking locks and related
 * synchronizers (semaphores, events, etc) that rely on
 * first-in-first-out (FIFO) wait queues.  This class is designed to
 * be a useful basis for most kinds of synchronizers that rely on a
 * single atomic {@code int} value to represent state. Subclasses
 * must define the protected methods that change this state, and which
 * define what that state means in terms of this object being acquired
 * or released.  Given these, the other methods in this class carry
 * out all queuing and blocking mechanics. Subclasses can maintain
 * other state fields, but only the atomically updated {@code int}
 * value manipulated using methods {@link #getState}, {@link
 * #setState} and {@link #compareAndSetState} is tracked with respect
 * to synchronization.
 *
 * <p>Subclasses should be defined as non-public internal helper
 * classes that are used to implement the synchronization properties
 * of their enclosing class.  Class
 * {@code AbstractQueuedSynchronizer} does not implement any
 * synchronization interface.  Instead it defines methods such as
 * {@link #acquireInterruptibly} that can be invoked as
 * appropriate by concrete locks and related synchronizers to
 * implement their public methods.
 *
 * <p>This class supports either or both a default <em>exclusive</em>
 * mode and a <em>shared</em> mode. When acquired in exclusive mode,
 * attempted acquires by other threads cannot succeed. Shared mode
 * acquires by multiple threads may (but need not) succeed. This class
 * does not &quot;understand&quot; these differences except in the
 * mechanical sense that when a shared mode acquire succeeds, the next
 * waiting thread (if one exists) must also determine whether it can
 * acquire as well. Threads waiting in the different modes share the
 * same FIFO queue. Usually, implementation subclasses support only
 * one of these modes, but both can come into play for example in a
 * {@link ReadWriteLock}. Subclasses that support only exclusive or
 * only shared modes need not define the methods supporting the unused mode.
 *
 * <p>This class defines a nested {@link ConditionObject} class that
 * can be used as a {@link Condition} implementation by subclasses
 * supporting exclusive mode for which method {@link
 * #isHeldExclusively} reports whether synchronization is exclusively
 * held with respect to the current thread, method {@link #release}
 * invoked with the current {@link #getState} value fully releases
 * this object, and {@link #acquire}, given this saved state value,
 * eventually restores this object to its previous acquired state.  No
 * {@code AbstractQueuedSynchronizer} method otherwise creates such a
 * condition, so if this constraint cannot be met, do not use it.  The
 * behavior of {@link ConditionObject} depends of course on the
 * semantics of its synchronizer implementation.
 *
 * <p>This class provides inspection, instrumentation, and monitoring
 * methods for the internal queue, as well as similar methods for
 * condition objects. These can be exported as desired into classes
 * using an {@code AbstractQueuedSynchronizer} for their
 * synchronization mechanics.
 *
 * <p>Serialization of this class stores only the underlying atomic
 * integer maintaining state, so deserialized objects have empty
 * thread queues. Typical subclasses requiring serializability will
 * define a {@code readObject} method that restores this to a known
 * initial state upon deserialization.
 *
 * <h3>Usage</h3>
 *
 * <p>To use this class as the basis of a synchronizer, redefine the
 * following methods, as applicable, by inspecting and/or modifying
 * the synchronization state using {@link #getState}, {@link
 * #setState} and/or {@link #compareAndSetState}:
 *
 * <ul>
 * <li> {@link #tryAcquire}
 * <li> {@link #tryRelease}
 * <li> {@link #tryAcquireShared}
 * <li> {@link #tryReleaseShared}
 * <li> {@link #isHeldExclusively}
 * </ul>
 *
 * Each of these methods by default throws {@link
 * UnsupportedOperationException}.  Implementations of these methods
 * must be internally thread-safe, and should in general be short and
 * not block. Defining these methods is the <em>only</em> supported
 * means of using this class. All other methods are declared
 * {@code final} because they cannot be independently varied.
 *
 * <p>You may also find the inherited methods from {@link
 * AbstractOwnableSynchronizer} useful to keep track of the thread
 * owning an exclusive synchronizer.  You are encouraged to use them
 * -- this enables monitoring and diagnostic tools to assist users in
 * determining which threads hold locks.
 *
 * <p>Even though this class is based on an internal FIFO queue, it
 * does not automatically enforce FIFO acquisition policies.  The core
 * of exclusive synchronization takes the form:
 *
 * <pre>
 * Acquire:
 *     while (!tryAcquire(arg)) {
 *        <em>enqueue thread if it is not already queued</em>;
 *        <em>possibly block current thread</em>;
 *     }
 *
 * Release:
 *     if (tryRelease(arg))
 *        <em>unblock the first queued thread</em>;
 * </pre>
 *
 * (Shared mode is similar but may involve cascading signals.)
 *
 * <p id="barging">Because checks in acquire are invoked before
 * enqueuing, a newly acquiring thread may <em>barge</em> ahead of
 * others that are blocked and queued.  However, you can, if desired,
 * define {@code tryAcquire} and/or {@code tryAcquireShared} to
 * disable barging by internally invoking one or more of the inspection
 * methods, thereby providing a <em>fair</em> FIFO acquisition order.
 * In particular, most fair synchronizers can define {@code tryAcquire}
 * to return {@code false} if {@link #hasQueuedPredecessors} (a method
 * specifically designed to be used by fair synchronizers) returns
 * {@code true}.  Other variations are possible.
 *
 * <p>Throughput and scalability are generally highest for the
 * default barging (also known as <em>greedy</em>,
 * <em>renouncement</em>, and <em>convoy-avoidance</em>) strategy.
 * While this is not guaranteed to be fair or starvation-free, earlier
 * queued threads are allowed to recontend before later queued
 * threads, and each recontention has an unbiased chance to succeed
 * against incoming threads.  Also, while acquires do not
 * &quot;spin&quot; in the usual sense, they may perform multiple
 * invocations of {@code tryAcquire} interspersed with other
 * computations before blocking.  This gives most of the benefits of
 * spins when exclusive synchronization is only briefly held, without
 * most of the liabilities when it isn't. If so desired, you can
 * augment this by preceding calls to acquire methods with
 * "fast-path" checks, possibly prechecking {@link #hasContended}
 * and/or {@link #hasQueuedThreads} to only do so if the synchronizer
 * is likely not to be contended.
 *
 * <p>This class provides an efficient and scalable basis for
 * synchronization in part by specializing its range of use to
 * synchronizers that can rely on {@code int} state, acquire, and
 * release parameters, and an internal FIFO wait queue. When this does
 * not suffice, you can build synchronizers from a lower level using
 * {@link java.util.concurrent.atomic atomic} classes, your own custom
 * {@link java.util.Queue} classes, and {@link LockSupport} blocking
 * support.
 *
 * <h3>Usage Examples</h3>
 *
 * <p>Here is a non-reentrant mutual exclusion lock class that uses
 * the value zero to represent the unlocked state, and one to
 * represent the locked state. While a non-reentrant lock
 * does not strictly require recording of the current owner
 * thread, this class does so anyway to make usage easier to monitor.
 * It also supports conditions and exposes
 * one of the instrumentation methods:
 *
 *  <pre> {@code
 * class Mutex implements Lock, java.io.Serializable {
 *
 *   // Our internal helper class
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     // Reports whether in locked state
 *     protected boolean isHeldExclusively() {
 *       return getState() == 1;
 *     }
 *
 *     // Acquires the lock if state is zero
 *     public boolean tryAcquire(int acquires) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {
 *         setExclusiveOwnerThread(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // Releases the lock by setting state to zero
 *     protected boolean tryRelease(int releases) {
 *       assert releases == 1; // Otherwise unused
 *       if (getState() == 0) throw new IllegalMonitorStateException();
 *       setExclusiveOwnerThread(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // Provides a Condition
 *     Condition newCondition() { return new ConditionObject(); }
 *
 *     // Deserializes properly
 *     private void readObject(ObjectInputStream s)
 *         throws IOException, ClassNotFoundException {
 *       s.defaultReadObject();
 *       setState(0); // reset to unlocked state
 *     }
 *   }
 *
 *   // The sync object does all the hard work. We just forward to it.
 *   private final Sync sync = new Sync();
 *
 *   public void lock()                { sync.acquire(1); }
 *   public boolean tryLock()          { return sync.tryAcquire(1); }
 *   public void unlock()              { sync.release(1); }
 *   public Condition newCondition()   { return sync.newCondition(); }
 *   public boolean isLocked()         { return sync.isHeldExclusively(); }
 *   public boolean hasQueuedThreads() { return sync.hasQueuedThreads(); }
 *   public void lockInterruptibly() throws InterruptedException {
 *     sync.acquireInterruptibly(1);
 *   }
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return sync.tryAcquireNanos(1, unit.toNanos(timeout));
 *   }
 * }}</pre>
 *
 * <p>Here is a latch class that is like a
 * {@link java.util.concurrent.CountDownLatch CountDownLatch}
 * except that it only requires a single {@code signal} to
 * fire. Because a latch is non-exclusive, it uses the {@code shared}
 * acquire and release methods.
 *
 *  <pre> {@code
 * class BooleanLatch {
 *
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     boolean isSignalled() { return getState() != 0; }
 *
 *     protected int tryAcquireShared(int ignore) {
 *       return isSignalled() ? 1 : -1;
 *     }
 *
 *     protected boolean tryReleaseShared(int ignore) {
 *       setState(1);
 *       return true;
 *     }
 *   }
 *
 *   private final Sync sync = new Sync();
 *   public boolean isSignalled() { return sync.isSignalled(); }
 *   public void signal()         { sync.releaseShared(1); }
 *   public void await() throws InterruptedException {
 *     sync.acquireSharedInterruptibly(1);
 *   }
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */
public abstract class AbstractQueuedSynchronizer
    extends AbstractOwnableSynchronizer
    implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * Creates a new {@code AbstractQueuedSynchronizer} instance
     * with initial synchronization state of zero.
     */
    protected AbstractQueuedSynchronizer() { }

    /**
     * Wait queue node class.
     *
     * <p>The wait queue is a variant of a "CLH" (Craig, Landin, and
     * Hagersten) lock queue. CLH locks are normally used for
     * spinlocks.  We instead use them for blocking synchronizers, but
     * use the same basic tactic of holding some of the control
     * information about a thread in the predecessor of its node.  A
     * "status" field in each node keeps track of whether a thread
     * should block.  A node is signalled when its predecessor
     * releases.  Each node of the queue otherwise serves as a
     * specific-notification-style monitor holding a single waiting
     * thread. The status field does NOT control whether threads are
     * granted locks etc though.  A thread may try to acquire if it is
     * first in the queue. But being first does not guarantee success;
     * it only gives the right to contend.  So the currently released
     * contender thread may need to rewait.
     *
     * <p>To enqueue into a CLH lock, you atomically splice it in as new
     * tail. To dequeue, you just set the head field.
     * <pre>
     *      +------+  prev +-----+       +-----+
     * head |      | <---- |     | <---- |     |  tail
     *      +------+       +-----+       +-----+
     * </pre>
     *
     * <p>Insertion into a CLH queue requires only a single atomic
     * operation on "tail", so there is a simple atomic point of
     * demarcation from unqueued to queued. Similarly, dequeuing
     * involves only updating the "head". However, it takes a bit
     * more work for nodes to determine who their successors are,
     * in part to deal with possible cancellation due to timeouts
     * and interrupts.
     *
     * <p>The "prev" links (not used in original CLH locks), are mainly
     * needed to handle cancellation. If a node is cancelled, its
     * successor is (normally) relinked to a non-cancelled
     * predecessor. For explanation of similar mechanics in the case
     * of spin locks, see the papers by Scott and Scherer at
     * http://www.cs.rochester.edu/u/scott/synchronization/
     *
     * <p>We also use "next" links to implement blocking mechanics.
     * The thread id for each node is kept in its own node, so a
     * predecessor signals the next node to wake up by traversing
     * next link to determine which thread it is.  Determination of
     * successor must avoid races with newly queued nodes to set
     * the "next" fields of their predecessors.  This is solved
     * when necessary by checking backwards from the atomically
     * updated "tail" when a node's successor appears to be null.
     * (Or, said differently, the next-links are an optimization
     * so that we don't usually need a backward scan.)
     *
     * <p>Cancellation introduces some conservatism to the basic
     * algorithms.  Since we must poll for cancellation of other
     * nodes, we can miss noticing whether a cancelled node is
     * ahead or behind us. This is dealt with by always unparking
     * successors upon cancellation, allowing them to stabilize on
     * a new predecessor, unless we can identify an uncancelled
     * predecessor who will carry this responsibility.
     *
     * <p>CLH queues need a dummy header node to get started. But
     * we don't create them on construction, because it would be wasted
     * effort if there is never contention. Instead, the node
     * is constructed and head and tail pointers are set upon first
     * contention.
     *
     * <p>Threads waiting on Conditions use the same nodes, but
     * use an additional link. Conditions only need to link nodes
     * in simple (non-concurrent) linked queues because they are
     * only accessed when exclusively held.  Upon await, a node is
     * inserted into a condition queue.  Upon signal, the node is
     * transferred to the main queue.  A special value of status
     * field is used to mark which queue a node is on.
     *
     * <p>Thanks go to Dave Dice, Mark Moir, Victor Luchangco, Bill
     * Scherer and Michael Scott, along with members of JSR-166
     * expert group, for helpful ideas, discussions, and critiques
     * on the design of this class.
     */
    static final class Node {
        /** Marker to indicate a node is waiting in shared mode */
        static final Node SHARED = new Node(); // 表示共享锁
        /** Marker to indicate a node is waiting in exclusive mode */
        static final Node EXCLUSIVE = null; // 表示独占锁、排他锁

        /** waitStatus value to indicate thread has cancelled */
        /**
         * INITIAL，值为0，初始状态。
         *
         * 0:新加入的节点
         */
        /**
         * CANCELLED，值为1 。场景：当该线程等待超时或者被中断，需要从同步队列中取消等待，则该线程被置1，即被取消（这里该线程在取消之前是等待状态）。节点进入了取消状态则不再变化；
         *
         * CANCELLED(1):该节点的线程可能由于超时或被中断而处于被取消(作废)状态,一旦处于这个状态,节点状态将一直处于CANCELLED(作废),因此应该从队列中移除.
         */
        static final int CANCELLED =  1;
        /** waitStatus value to indicate successor's thread needs unparking */
        /**
         * SIGNAL，值为-1。场景：后继的节点处于等待状态，当前节点的线程如果释放了同步状态或者被取消（当前节点状态置为-1），将会通知后继节点，使后继节点的线程得以运行
         *
         * SIGNAL(-1):当前节点为SIGNAL时,后继节点会被挂起,因此在当前节点释放锁或被取消之后必须被唤醒(unparking)其后继结点.
         */
        static final int SIGNAL    = -1;
        /** waitStatus value to indicate thread is waiting on condition */
        /**
         * CONDITION，值为-2。场景：节点处于等待队列中，节点线程等待在Condition上，当其他线程对Condition调用了signal()方法后，该节点从等待队列中转移到同步队列中，加入到对同步状态的获取中
         *
         * CONDITION(-2) 该节点的线程处于等待条件状态,不会被当作是同步队列上的节点,直到被唤醒(signal),设置其值为0,重新进入阻塞状态.
         */
        static final int CONDITION = -2;
        /**
         * waitStatus value to indicate the next acquireShared should
         * unconditionally propagate
         */
        /**
         * PROPAGATE，值为-3。场景：表示下一次的共享状态会被无条件的传播下去
         */
        static final int PROPAGATE = -3;

        /**
         * Status field, taking on only the values:
         *   SIGNAL:     The successor of this node is (or will soon be)
         *               blocked (via park), so the current node must
         *               unpark its successor when it releases or
         *               cancels. To avoid races, acquire methods must
         *               first indicate they need a signal,
         *               then retry the atomic acquire, and then,
         *               on failure, block.
         *   CANCELLED:  This node is cancelled due to timeout or interrupt.
         *               Nodes never leave this state. In particular,
         *               a thread with cancelled node never again blocks.
         *   CONDITION:  This node is currently on a condition queue.
         *               It will not be used as a sync queue node
         *               until transferred, at which time the status
         *               will be set to 0. (Use of this value here has
         *               nothing to do with the other uses of the
         *               field, but simplifies mechanics.)
         *   PROPAGATE:  A releaseShared should be propagated to other
         *               nodes. This is set (for head node only) in
         *               doReleaseShared to ensure propagation
         *               continues, even if other operations have
         *               since intervened.
         *   0:          None of the above
         *
         * The values are arranged numerically to simplify use.
         * Non-negative values mean that a node doesn't need to
         * signal. So, most code doesn't need to check for particular
         * values, just for sign.
         *
         * The field is initialized to 0 for normal sync nodes, and
         * CONDITION for condition nodes.  It is modified using CAS
         * (or when possible, unconditional volatile writes).
         */
        volatile int waitStatus; // 以上状态

        /**
         * Link to predecessor node that current node/thread relies on
         * for checking waitStatus. Assigned during enqueuing, and nulled
         * out (for sake of GC) only upon dequeuing.  Also, upon
         * cancellation of a predecessor, we short-circuit while
         * finding a non-cancelled one, which will always exist
         * because the head node is never cancelled: A node becomes
         * head only as a result of successful acquire. A
         * cancelled thread never succeeds in acquiring, and a thread only
         * cancels itself, not any other node.
         */
        volatile Node prev; // 前驱节点，当节点加入同步队列的时候被设置（尾部添加）

        /**
         * Link to the successor node that the current node/thread
         * unparks upon release. Assigned during enqueuing, adjusted
         * when bypassing cancelled predecessors, and nulled out (for
         * sake of GC) when dequeued.  The enq operation does not
         * assign next field of a predecessor until after attachment,
         * so seeing a null next field does not necessarily mean that
         * node is at end of queue. However, if a next field appears
         * to be null, we can scan prev's from the tail to
         * double-check.  The next field of cancelled nodes is set to
         * point to the node itself instead of null, to make life
         * easier for isOnSyncQueue.
         */
        volatile Node next; // 后继节点

        /**
         * The thread that enqueued this node.  Initialized on
         * construction and nulled out after use.
         */
        volatile Thread thread; // 获取同步状态的线程

        /**
         * Link to next node waiting on condition, or the special
         * value SHARED.  Because condition queues are accessed only
         * when holding in exclusive mode, we just need a simple
         * linked queue to hold nodes while they are waiting on
         * conditions. They are then transferred to the queue to
         * re-acquire. And because conditions can only be exclusive,
         * we save a field by using special value to indicate shared
         * mode.
         */
        /**
         * 等待节点的后继节点。如果当前节点是共享的，那么这个字段是一个SHARED常量，也就是说节点类型（独占和共享）和等待队列中的后继节点共用一个字段。
         * （注：比如说当前节点A是共享的，那么它的这个字段是shared，也就是说在这个等待队列中，A节点的后继节点也是shared。如果A节点不是共享的，
         * 那么它的nextWaiter就不是一个SHARED常量，即是独占的。）
         */
        Node nextWaiter;

        /**
         * Returns true if node is waiting in shared mode.
         */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * Returns previous node, or throws NullPointerException if null.
         * Use when predecessor cannot be null.  The null check could
         * be elided, but is present to help the VM.
         *
         * @return the predecessor of this node
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev; // 拿到当前节点的上一个节点

            /**
             *  想一想，这里为什么上一个节点如果是null要抛异常？head的prev不就是null吗？
             *  head节点是正在持有锁的Node，所以不可能到这里，不可能持有锁的Node线程还需要加入            *  AQS吧
             */
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        Node() {    // Used to establish initial head or SHARED marker
        }

        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        Node(Thread thread, int waitStatus) { // Used by Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * Head of the wait queue, lazily initialized.  Except for
     * initialization, it is modified only via method setHead.  Note:
     * If head exists, its waitStatus is guaranteed not to be
     * CANCELLED.
     */
    private transient volatile Node head;

    /**
     * Tail of the wait queue, lazily initialized.  Modified only via
     * method enq to add new wait node.
     */
    private transient volatile Node tail;

    /**
     * The synchronization state.
     */
    private volatile int state;

    /**
     * Returns the current value of synchronization state.
     * This operation has memory semantics of a {@code volatile} read.
     * @return current state value
     */
    protected final int getState() {
        return state;
    }

    /**
     * Sets the value of synchronization state.
     * This operation has memory semantics of a {@code volatile} write.
     * @param newState the new state value
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * Atomically sets synchronization state to the given updated
     * value if the current state value equals the expected value.
     * This operation has memory semantics of a {@code volatile} read
     * and write.
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful. False return indicates that the actual
     *         value was not equal to the expected value.
     */

    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        /**
         * this : 该对象
         * stateOffset : state在内存中的存储位置(long)
         * expect : 期望值(0 末有线程加锁)
         * update : 加锁线程(1 或更大(重入锁))
         */
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // Queuing utilities

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices
     * to improve responsiveness with very short timeouts.
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * Inserts node into queue, initializing if necessary. See picture above.
     * @param node the node to insert
     * @return node's predecessor
     */
    /**
     * 当tail节点不存在时，无限循环创建head与tail节点
     */
    private Node enq(final Node node) {
        for (;;) {
            Node t = tail; // 尾节点
            if (t == null) { // Must initialize // 进行初始化
                /**
                 *  CAS向head写入"空"节点，为什么head要是"空"Node？没有prev，没有CurrentThread，没有next。因为head节点表示的是当前正在持有锁的线程，初始化时，就创建了一 个"空"Node
                 */
                if (compareAndSetHead(new Node()))
                    tail = head; // 将head赋给tail
            } else {
                node.prev = t; // 当前节点的prev指向tail
                if (compareAndSetTail(t, node)) { // 使用CAS方式将AQS链表的tail节点更新成当前节点(刚刚新加入，当然是尾节点tail)
                    t.next = node; // 如果更新成功，将原tail节点的next指向当前节点
                    return t; // 返回当前节点，刚刚加入AQS队列的节点
                }
            }
        }
        /**
         * 当AQS队列tail节点为NULL，此时说明AQS队列还未初始化
         *  第一次for循环：tail节点是NULL(未初始化)，创建一个"空"Node节点CAS方式更新head，并将head赋给tail(此时并未使用CAS方式更新tail)
         *  第二次for循环：tail经不为NULL，将当前Node的prev指向tail，CAS方式更新tail为当前节点，将原tail节点的next指向当前节点，最终返回
         */
    }

    /**
     * Creates and enqueues node for current thread and given mode.
     *
     * @param mode Node.EXCLUSIVE for exclusive, Node.SHARED for shared
     * @return the new node
     */
    private Node addWaiter(Node mode) {
        Node node = new Node(Thread.currentThread(), mode); // 用将当前线程创建一个AQS中的Node节点；并用mode(Node)表示锁类型
        // Try the fast path of enq; backup to full enq on failure
        Node pred = tail; // 拿到尾部节点
        if (pred != null) { // 如果尾部节点不为NULL，说明AQS队列中已经有在等待锁释放的线程
            node.prev = pred; // 将当前节点的prev指向tail(尾部节点)
            if (compareAndSetTail(pred, node)) { // 使用CAS方式将AQS链表的tail节点更新成当前节点(刚刚新加入，当然是尾节点tail)
                pred.next = node; // 如果更新成功，将原tail节点的next指向当前节点
                return node; // 返回当前节点，刚刚加入AQS队列的节点
            }
        }
        // 如果tail节点是null
        enq(node); // 初始化AQS队列；操作后，head节点是"空"Node，tail节点是当前节点
        return node;
    }

    /**
     * Sets head of queue to be node, thus dequeuing. Called only by
     * acquire methods.  Also nulls out unused fields for sake of GC
     * and to suppress unnecessary signals and traversals.
     *
     * @param node the node
     */
    private void setHead(Node node) {
        head = node; // 将当前节点赋予head
        /**
         * Node的thread置NULL，因为当前节点已经准许持有锁，不阻塞，
         * 正常运行。不需要再对thread做什么，所以node不再需thread
         */
        node.thread = null;
        node.prev = null; // prev node置null
    }

    /**
     * Wakes up node's successor, if one exists.
     *
     * @param node the node
     */
    private void unparkSuccessor(Node node) {
        /*
         * If status is negative (i.e., possibly needing signal) try
         * to clear in anticipation of signalling.  It is OK if this
         * fails or if status is changed by waiting thread.
         */
        int ws = node.waitStatus; // 当前节点的等待状态
        if (ws < 0) // 状态值小于0
            compareAndSetWaitStatus(node, ws, 0); // 将状态值CAS修改成0表示初始化

        /*
         * Thread to unpark is held in successor, which is normally
         * just the next node.  But if cancelled or apparently null,
         * traverse backwards from tail to find the actual
         * non-cancelled successor.
         */
        Node s = node.next; // 拿到后继节点
        /**
         * 后继节点为NULL或等待状态大于0
         * 只有1，等待超时或者被中断，需要从同步队列中取消等待
         */
        if (s == null || s.waitStatus > 0) {
            s = null; // 后继节点置NULL
            /**
             * 如果后继节点被取消或显然为空，则从tail向前遍历，以找到实际的未取消后继
             *
             * t != null && t != node 头节点或者是当前节点会不满足条件
             */
            for (Node t = tail; t != null && t != node; t = t.prev) // 从tail开始向前遍历AQS队列
                if (t.waitStatus <= 0) // 如果是有效节点(0初始化、1超时或要取消等待)
                    s = t;
        }
        if (s != null) // s节点不等于NULL，将s节点唤醒
            LockSupport.unpark(s.thread);
    }

    /**
     * Release action for shared mode -- signals successor and ensures
     * propagation. (Note: For exclusive mode, release just amounts
     * to calling unparkSuccessor of head if it needs signal.)
     */
    private void doReleaseShared() {
        /*
         * Ensure that a release propagates, even if there are other
         * in-progress acquires/releases.  This proceeds in the usual
         * way of trying to unparkSuccessor of head if it needs
         * signal. But if it does not, status is set to PROPAGATE to
         * ensure that upon release, propagation continues.
         * Additionally, we must loop in case a new node is added
         * while we are doing this. Also, unlike other uses of
         * unparkSuccessor, we need to know if CAS to reset status
         * fails, if so rechecking.
         */
        for (;;) { // 无限循环
            Node h = head; // 头节点
            /**
             * 如果头节点不是NULL并且头节点不是尾节点，说明是有节点持有锁并且当前队列中是有其余节点在等待
             */
            if (h != null && h != tail) {
                int ws = h.waitStatus; // 头节点的等待状态
                if (ws == Node.SIGNAL) { // Node.SIGNAL：当前节点释放锁或取消，通知后继节点继续运行，需要这个信号才能唤醒后继线程节点
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0)) // 将释放锁或取消线程的节点状态CAS改为0(初始化)
                        continue;            // loop to recheck cases 如果更新失败，就循环重试此头节点
                    unparkSuccessor(h); // 唤醒后继节点线程(h的后继节点，在方法中h.next)
                }
                else if (ws == 0 && // 状态是初始化状态
                        /**
                         * CAS更改等待状态为Node.PROPAGATE(-3)，表示下一次的共享状态会被无条件的传播下去
                         */
                         !compareAndSetWaitStatus(h, 0, Node.PROPAGATE)) // 修改失败，就循环重试
                    continue;                // loop on failed CAS
            }
            /**
             * 在循环过程中，为了防止在上述操作过程中新添加了节点的情况，通过检查头节点是否改变了，如果改变了就继续循环
             */
            if (h == head)                   // loop if head changed
                break;
        }
    }

    /**
     * Sets head of queue, and checks if successor may be waiting
     * in shared mode, if so propagating if either propagate > 0 or
     * PROPAGATE status was set.
     *
     * @param node the node
     * @param propagate the return value from a tryAcquireShared
     */

    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // Record old head for check below
        setHead(node); // 设置头节点为传参节点(当前节点)
        /*
         * Try to signal next queued node if:
         *   Propagation was indicated by caller,
         *     or was recorded (as h.waitStatus either before
         *     or after setHead) by a previous operation
         *     (note: this uses sign-check of waitStatus because
         *      PROPAGATE status may transition to SIGNAL.)
         * and
         *   The next node is waiting in shared mode,
         *     or we don't know, because it appears null
         *
         * The conservatism in both of these checks may cause
         * unnecessary wake-ups, but only when there are multiple
         * racing acquires/releases, so most need signals now or soon
         * anyway.
         */
        /**
         * propagate > 0：说明是当前节点成功加了共享锁
         * h == null：无持有锁的节点
         * h.waitStatus < 0：正常状态，线程没被阻断
         * (h = head) == null || h.waitStatus < 0) // 再次赋值并判断
         */
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
            (h = head) == null || h.waitStatus < 0) {
            Node s = node.next; // 拿到AQS中的下一个节点
            if (s == null || s.isShared()) // 如果后继节点不存在，或后继节点是共享锁的线程节点
                doReleaseShared(); // 然后进行共享模式的释放
        }
        /**
         * 此方法大体意思是，先将当前节点设置为头节点
         * 再判断：如果此节点是成功获取读锁的，或是之前head是NULL(没人持有锁)，或者是状态正常
         *        的线程(不被阻断、停止)；或现在headNULL(没人持有锁)，或者是状态正常的线程(不被
         *        阻断、停止)；说明当前持有锁的节点一定不是独占锁，就可以唤醒正在排队的共享节点
         */
    }

    // Utilities for various versions of acquire

    /**
     * Cancels an ongoing attempt to acquire.
     *
     * @param node the node
     */
    private void cancelAcquire(Node node) {
        // Ignore if node doesn't exist
        if (node == null)
            return;

        node.thread = null; // 清除结点线程

        // Skip cancelled predecessors
        Node pred = node.prev; // 拿到当前节点的上一个节点
        while (pred.waitStatus > 0) // CANCELLED，值为1，当该线程(前驱节点)等待超时或者被中断，需要从同步队列中取消等待，即被取消
            node.prev = pred = pred.prev; // 删除等待超时或被中断的上一个节点

        // predNext is the apparent node to unsplice. CASes below will
        // fail if not, in which case, we lost race vs another cancel
        // or signal, so no further action is necessary.
        Node predNext = pred.next; // 如果while执行了，pred会被替换，这个值就是最新pred的next，所以predNext可能不是当前节点

        // Can use unconditional write instead of CAS here.
        // After this atomic step, other Nodes can skip past us.
        // Before, we are free of interference from other threads.
        node.waitStatus = Node.CANCELLED; // 当前节点，设置为1，当该线程等待超时或者被中断，需要从同步队列中取消等待，即被取消

        // If we are the tail, remove ourselves.
        if (node == tail && compareAndSetTail(node, pred)) { // 如果当前节点是tail节点(尾节点)并且将新前驱成功设置成tail节点(node是原始值，pred是要更新的值)
            /**
             * CAS方式更新前驱节点的next是null(pred是要更新的对象，predNext是原始值(可能是当前节点、可能是最新pred的next，但不重要)，null是新值)
             * 因为当前节点是tail并且要移除，所以前驱是tail，前驱的next是null
             */
            compareAndSetNext(pred, predNext, null);
        } else { // 当前节点不是tail或将pred(最有效的前驱)CAS更新为tail失败
            // If successor needs signal, try to set pred's next-link
            // so it will get one. Otherwise wake it up to propagate.
            int ws;
            if (pred != head && // 前驱节点不是锁的持有者
                ((ws = pred.waitStatus) == Node.SIGNAL || // 前驱节点的等待状态是-1，表示pred释放了同步状态或者被取消，将通知后继节点得以运行(pred已经释放锁了)
                /**
                 * ws <= 0：
                 * INITIAL，值为0，初始状态
                 * static final int SIGNAL    = -1; // 后继的节点处于等待状态，当前节点的线程如果释放了同步状态或者被取消
                 * static final int CONDITION = -2; // 该节点从等待队列中转移到同步队列中，加入到对同步状态的获取中(Condition调用了signal()方法后)
                 * static final int PROPAGATE = -3; // 表示下一次的共享状态会被无条件的传播下去
                 */
                 (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) && // pred的等待状态小于等于0并且成功将等待状态设置为Node.SIGNA -1
                pred.thread != null) { // 并且pred的CurrentThread不为NULL
                Node next = node.next; // 当前节点的后继节点
                if (next != null && next.waitStatus <= 0) // next不为NULL并且next.waitStatus小于等于0：0、-1、-2、-3
                    compareAndSetNext(pred, predNext, next); // CAS更新前驱结点的后继结点：更新对象pred，原值predNext，要更新的值next
            } else {
                unparkSuccessor(node);
            }

            node.next = node; // help GC // 当前节点的后继指向自己，等待回收资源
        }
        /**
         * 删除思想：
         * 1、要移除当前节点，首选看前驱节点是否需要被移除；如果需要移除，就将前驱的前驱节点变成当前节点的前驱节点(所有的后继节点不变)
         * 2、如果当前节点是tail节点，并且将前驱节点(可能是更改过的)CAS方式更新成tail；如果成功，CAS更新tail的next是NULL
         * 3、否则当前节点不是tail或将前驱节点更新为tail失败后必需满足：
         *      前驱不是锁的持有者&&{[前驱的等待状态是Node.SIGNAL -1] || [前驱的等待状态<=0&&可以将等待状态CAS更新成Node.SIGNAL -1]}
         *      &&pred的CurrentThread不为NULL
         */
    }

    /**
     * Checks and updates status for a node that failed to acquire.
     * Returns true if thread should block. This is the main signal
     * control in all acquire loops.  Requires that pred == node.prev.
     *
     * @param pred node's predecessor holding status
     * @param node the node
     * @return {@code true} if thread should block
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        /**
         * 后继的节点处于等待状态，当前节点的线程如果释放了同步状态或者被取消（当前节点状态置为-1）
         * 将会通知后继节点，使后继节点的线程得以运行
         */
        int ws = pred.waitStatus; // 上一个节点的状态
        if (ws == Node.SIGNAL) // 如果当前节点的上一个节点释放了同步状态或被取消
            /*
             * This node has already set status asking a release
             * to signal it, so it can safely park.
             */
            return true; // 通知后继节点(阻断后继线程，因为后继线程在那hang着(当前节点的前驱节点通知后继节点，就是当前节点))
        /**
         * CANCELLED，值为1 。场景：当该线程等待超时或者被中断，需要从同步队列中取消等待，则该线程被置1，
         * 即被取消（这里该线程在取消之前是等待状态）。节点进入了取消状态则不再变化；
         */
        if (ws > 0) { // 如果上一个节点等待超时、被中断，则从AQS队列中移除节点
            /*
             * Predecessor was cancelled. Skip over predecessors and
             * indicate retry.
             */
            do {
                /**
                 * 将当前节点的祖父节点赋给当前节点的父节点
                 * 将当前节点的prev指向新的父节点
                 */
                node.prev = pred = pred.prev; // 删除等待超时或被中断的上一个节点
            } while (pred.waitStatus > 0); // 迭代遍历直到当前节点的上一个节点不再是待超时或被中断的
            pred.next = node; // 将那个不再是待超时或被中断的节点的next指向当前节点
        } else {
            /*
             * waitStatus must be 0 or PROPAGATE.  Indicate that we
             * need a signal, but don't park yet.  Caller will need to
             * retry to make sure it cannot acquire before parking.
             */
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL); // 将当前节点的上一个节点的等待状态CAS更新为Node.SIGNAL
        }
        return false; // 返回当前线程不被阻塞、不需要被挂起
    }

    /**
     * Convenience method to interrupt current thread.
     */
    /**
     *  阻断线程
     */
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * Convenience method to park and then check if interrupted
     *
     * @return {@code true} if interrupted
     */
    private final boolean parkAndCheckInterrupt() {
        /**
         * LockSupport的park操作，就是将一个线程进行挂起，不让你动了
         * 必须得有另外一个线程来对当前线程执行unpark操作，唤醒挂起的线程
         */
        LockSupport.park(this);
        /**
         * 测试当前线程是否已被中断
         * 如果此方法要连续调用两次，则第二次调用将返回false（除非当前线程为在第一个呼叫清除其中断后，再次中断状态，并且在第二个电话对其进行检查之前)
         * 由于线程未激活，因此忽略了线程中断，中断时将通过此方法反映返回false
         * 如果当前线程已被中断返回true，否则返回false
         */
        return Thread.interrupted(); // 返回线程是否被中断
    }

    /*
     * Various flavors of acquire, varying in exclusive/shared and
     * control modes.  Each is mostly the same, but annoyingly
     * different.  Only a little bit of factoring is possible due to
     * interactions of exception mechanics (including ensuring that we
     * cancel if tryAcquire throws exception) and other control, at
     * least not without hurting performance too much.
     */

    /**
     * Acquires in exclusive uninterruptible mode for thread already in
     * queue. Used by condition wait methods as well as acquire.
     *
     * @param node the node
     * @param arg the acquire argument
     * @return {@code true} if interrupted while waiting
     */
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false; // 返回变量，表示是否挂起当前线程
            for (;;) { // 无限循环
                final Node p = node.predecessor(); // 拿到当前Node的上一个Node

                /**
                 *  如果上一个节点是head，并且尝试当前线程再次加锁成功
                 *
                 *  这个if表示什么呢？
                 *  head表示已经持有锁的节点，p表示当前节点的上一个节点；
                 *  如果当前节点的上一个节点是head(正在持有锁的节点)，并且当前节点再次尝试加锁成功
                 *  就说明，持有锁的线程已经将锁释放了，并且当前节点成功持有了锁(成功加锁)，就将head设置成当前线程
                 *  head没有prev，没有CurrentThread，因为已经不需要了，当前线程不需要阻断，放其运行就好
                 */
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC // help GC 上一个持有锁的线程已经释放了锁，节点资源可以回收
                    failed = false; // 当前线程失败设置成false，因为当前线程已经是持有锁的线程了
                    return interrupted; // 线程是否阻断也是false，当前线程持有了锁，准许正常运行，不需要阻断
                }
                // 到了这里就说明当前线程没有加锁成功，并没有成为持有锁的head
                if (shouldParkAfterFailedAcquire(p, node) && // 当前线程是否需要被阻塞，如果需要，就向下执行，阻塞、挂起线程
                    /**
                     * 将该节点挂起，当被唤醒后，并返回是否被阻断
                     * 当线程被调用part方法挂起后，什么情况下会被唤醒？是调用unpart方法的时候，或还有一种极端的情况，
                     * 调用interrupt()方法会阻断正在挂起的线程，就是让线程得以继续执行，从而并没有起到线程中断的作用，
                     * 所以parkAndCheckInterrupt()方法才要返回是否线程被中断，如果中断位是true(实际中断的是挂起操作)
                     * 那就将标志位返回上一级，从而将线程真正的中断抛出InterruptedException异常
                     */
                    parkAndCheckInterrupt())
                    /**
                     * 如果线程被阻断(为什么线程被阻断还要继续阻断？线程被挂起的时候阻断的是挂起，而要真正的阻断线程，就得再阻断)
                     * 返回true表示要阻断该节点
                     */
                    interrupted = true;
            }
            /**
             * 总结for无限循环这段代码：
             * 1、先拿到当前节点的上一个节点
             * 2、如果上一个节点是head，就说明上一个节点是持有锁的线程，并尝试当前节点获取锁，
             *    如果上一节点是head并释放了锁，那当前节点就能加锁成功，成为持有锁的节点；
             * 3、将当前节点设置成head，并把上一个持有锁的head的next置NULL释放资源；tailed设置为false
             *    (不把当前节点从AQS队列中取消)，interrupted返回false(不中断该线程)
             * 4、如果当前节点的上一个节点不是head，说明前面还有更多节点在排队或者当前节点的
             *    上一个节点是head但加锁失败了，会做判断：如果当前线程被阻塞，并将当前线程挂起，就是hang在那
             *    什么也不做，直到有人唤醒他才会继续向下运行：返回当前线程状态标志是否被设置中断(并清除interrupted状态)
             * 5、如果当前线程被阻塞，并且parkAndCheckInterrupt()返回了，说明被人唤醒，被hang住的时代过去了；
             *    如果线程中断状态为true(interrupted=true)，将当前interrupted设为true，回到上一级并中断线程
             * 6、当第一轮for结束后，可以确定的是：当前节点没能成为锁的持有者后会被hang在那，并等待其它线程
             *    唤醒。随即被唤醒后，会重进入下一轮for；如此反复后，直到当前节点成为锁的持有者后才返回
             */
        } finally {
            if (failed) // 如果程序正常运行，无限循环会直到当前节点加锁成功，如果代码出了异常，循环结束会走到下面
                cancelAcquire(node); // 取消当前节点在AQS队列
        }
    }

    /**
     * Acquires in exclusive interruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireInterruptibly(int arg)
        throws InterruptedException {
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in exclusive timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L) // 判断尝试锁超时时间
            return false;
        final long deadline = System.nanoTime() + nanosTimeout; // 当前时间 + 锁超时时间 = 过期时间
        final Node node = addWaiter(Node.EXCLUSIVE); // 将当前线程封装成独占锁Node，并加入AQS队列
        boolean failed = true; // 失败标识
        try {
            for (;;) { // 无限循环
                final Node p = node.predecessor(); // 拿到当前Node的上一个Node
                /**
                 *  如果上一个节点是head，并且尝试当前线程再次加锁成功
                 *
                 *  这个if表示什么呢？
                 *  head表示已经持有锁的节点，p表示当前节点的上一个节点；
                 *  如果当前节点的上一个节点是head(正在持有锁的节点)，并且当前节点再次尝试加锁成功
                 *  就说明，持有锁的线程已经将锁释放了，并且当前节点成功持有了锁(成功加锁)，就将head设置成当前线程
                 *  head没有prev，没有CurrentThread，因为已经不需要了，当前线程不需要阻断，放其运行就好
                 */
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC 上一个持有锁的线程已经释放了锁，节点资源可以回收
                    failed = false; // 当前线程失败设置成false，因为当前线程已经是持有锁的线程了
                    return true; // 返回尝试成功加锁
                }
                nanosTimeout = deadline - System.nanoTime(); // 到这里，如果加锁失败，过期时间 - 当前时间 = 剩余重试加锁时间
                if (nanosTimeout <= 0L) // 如果重试加锁时间小于等于0，返回尝试失败
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) && // 当前线程是否需要被阻塞，如果需要，就向下执行，阻塞、挂起线程
                    nanosTimeout > spinForTimeoutThreshold) // 剩余时间大于1000L
                    /**
                     * 挂起剩余的时间，过期后自动释放阻塞，并for再一次尝试获取锁，如果再失败就返回尝试失败，反之返回尝试成功
                     */
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted()) // 如果线程被阻断，抛出异常
                    throw new InterruptedException();
            }
        } finally {
            if (failed) // 如果程序正常运行，无限循环会直到当前节点加锁成功，如果代码出了异常，循环结束会走到下面
                cancelAcquire(node); // 取消当前节点在AQS队列
        }
    }

    /**
     * Acquires in shared uninterruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireShared(int arg) {
        final Node node = addWaiter(Node.SHARED); // Node.SHARED表示共享锁，将当前线程封装Node，并加入AQS队列
        boolean failed = true; // 失败标识
        try {
            boolean interrupted = false; // 是否阻断线程
            for (;;) { // 无限循环
                final Node p = node.predecessor(); // 拿到当节点的前驱节点
                if (p == head) { // 如果当前节点的前驱节点是head，说明当前节点的前驱节点是持有锁的节点
                    int r = tryAcquireShared(arg); // 对当前节点尝试获取共享锁
                    if (r >= 0) { // r大于0，成功获取共享锁
                        setHeadAndPropagate(node, r); // 设置当前节点为头节点，并将读锁向下传播(释放队列排队的读锁)
                        p.next = null; // help GC 回收资源
                        if (interrupted) // 如果要线程要被阻断
                            selfInterrupt(); // 阻断线程
                        failed = false; // 失败标识设置false，否则后续会处理回收资源
                        return; // 已经成功获取共享锁，无需阻塞，正常返回，向后执行
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) && // 当前线程是否需要被阻塞，如果需要，就向下执行，阻塞、挂起线程
                    parkAndCheckInterrupt()) // 将该节点挂起，当被唤醒后，并返回是否被阻断
                    /**
                     * 如果线程被阻断(为什么线程被阻断还要继续阻断，看partAndCheckInterrupt()方法注释)
                     * 返回true表示要阻断该节点
                     */
                    interrupted = true;
            }
        } finally {
            if (failed) // 如果程序正常运行，无限循环会直到当前节点加锁成功，如果代码出了异常，循环结束会走到下面
                cancelAcquire(node); // 取消当前节点在AQS队列
        }
    }

    /**
     * Acquires in shared interruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireSharedInterruptibly(int arg)
        throws InterruptedException {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L) // 判断尝试锁超时时间
            return false;
        final long deadline = System.nanoTime() + nanosTimeout; // 当前时间 + 锁超时时间 = 过期时间
        final Node node = addWaiter(Node.SHARED); // 将当前线程封装成共享锁Node，并加入AQS队列
        boolean failed = true; // 失败标识
        try {
            for (;;) { // 无限循环
                final Node p = node.predecessor(); // 拿到当前Node的上一个Node
                if (p == head) { // 如果前驱节点是头节点，说前驱节点是持有锁的节点
                    int r = tryAcquireShared(arg);  // 大于0，成功加共享锁，小于0，失败
                    if (r >= 0) { // 成功加了共享锁
                        setHeadAndPropagate(node, r); // 设置当前节点为头节点，并将读锁向下传播(释放队列排队的读锁)
                        p.next = null; // help GC 上一个持有锁的线程已经释放了锁，节点资源可以回收
                        failed = false; // 当前线程失败设置成false，因为当前线程已经是持有锁的线程了
                        return true; // 返回尝试成功加锁
                    }
                }
                nanosTimeout = deadline - System.nanoTime(); // 到这里，如果加锁失败，过期时间 - 当前时间 = 剩余重试加锁时间
                if (nanosTimeout <= 0L) // 如果重试加锁时间小于等于0，返回尝试失败
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) && // 当前线程是否需要被阻塞，如果需要，就向下执行，阻塞、挂起线程
                    nanosTimeout > spinForTimeoutThreshold) // 剩余时间大于1000L
                    LockSupport.parkNanos(this, nanosTimeout); // 挂起剩余的时间，过期后自动释放阻塞，并for再一次尝试获取锁，如果再失败就返回尝试失败，反之返回尝试成功
                if (Thread.interrupted())
                    throw new InterruptedException(); // 如果线程被阻断，抛出异常
            }
        } finally {
            if (failed) // 如果程序正常运行，无限循环会直到当前节点加锁成功，如果代码出了异常，循环结束会走到下面
                cancelAcquire(node); // 取消当前节点在AQS队列
        }
    }

    // Main exported methods

    /**
     * Attempts to acquire in exclusive mode. This method should query
     * if the state of the object permits it to be acquired in the
     * exclusive mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread. This can be used
     * to implement method {@link Lock#tryLock()}.
     *
     * <p>The default
     * implementation throws {@link UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     *         been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in exclusive
     * mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this object is now in a fully released
     *         state, so that any waiting threads may attempt to acquire;
     *         and {@code false} otherwise.
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     *         mode succeeded but no subsequent shared-mode acquire can
     *         succeed; and a positive value if acquisition in shared
     *         mode succeeded and subsequent shared-mode acquires might
     *         also succeed, in which case a subsequent waiting thread
     *         must check availability. (Support for three different
     *         return values enables this method to be used in contexts
     *         where acquires only sometimes act exclusively.)  Upon
     *         success, this object has been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in shared mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     *         waiting acquire (shared or exclusive) to succeed; and
     *         {@code false} otherwise
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code true} if synchronization is held exclusively with
     * respect to the current (calling) thread.  This method is invoked
     * upon each call to a non-waiting {@link ConditionObject} method.
     * (Waiting methods instead invoke {@link #release}.)
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}. This method is invoked
     * internally only within {@link ConditionObject} methods, so need
     * not be defined if conditions are not used.
     *
     * @return {@code true} if synchronization is held exclusively;
     *         {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * Acquires in exclusive mode, ignoring interrupts.  Implemented
     * by invoking at least once {@link #tryAcquire},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquire} until success.  This method can be used
     * to implement method {@link Lock#lock}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     */
    /**
     * !tryAcquire(arg) : 加锁；可能是重入锁，也可能是持有锁的线程释放了锁，如果加锁成功，该干什么干什么
     *
     * addWaiter(Node.EXCLUSIVE) :
     *  )、static final Node EXCLUSIVE = null; 表示此锁是排他锁
     *  )、将当前线程封装Node，并加入AQS队列
     *
     * acquireQueued(addWaiter(Node.EXCLUSIVE), arg) :
     *  尝试再次加锁(严谨性，重入锁/持有锁线程释放锁)；加锁成功返回false(线程挂起失败)，加锁失败将线程挂起返回true
     */
    public final void acquire(int arg) {
        if (!tryAcquire(arg) && // 如果加锁失败
            acquireQueued(addWaiter(Node.EXCLUSIVE), arg)) // 将线程加入队列，并且成功将当前线程(刚刚加入队列)挂起
            selfInterrupt(); // Thread.currentThread().interrupt();  当前线程阻断

        /**
         * !tryAcquire(arg) : 加锁；可能是重入锁，也可能是持有锁的线程释放了锁，如果加锁成功，该干什么干什么
         * addWaiter(Node.EXCLUSIVE) :
         *  )、static final Node EXCLUSIVE = null; 表示此锁是排他锁
         *  )、将当前线程封装Node，并加入AQS队列
         * acquireQueued(addWaiter(Node.EXCLUSIVE), arg) :
         *  尝试再次加锁(严谨性，重入锁/持有锁线程释放锁)；加锁成功返回false(线程挂起失败)，加锁失败将线程挂起返回true
         */
    }

    /**
     * Acquires in exclusive mode, aborting if interrupted.
     * Implemented by first checking interrupt status, then invoking
     * at least once {@link #tryAcquire}, returning on
     * success.  Otherwise the thread is queued, possibly repeatedly
     * blocking and unblocking, invoking {@link #tryAcquire}
     * until success or the thread is interrupted.  This method can be
     * used to implement method {@link Lock#lockInterruptibly}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (!tryAcquire(arg))
            doAcquireInterruptibly(arg);
    }

    /**
     * Attempts to acquire in exclusive mode, aborting if interrupted,
     * and failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquire}, returning on success.  Otherwise, the thread is
     * queued, possibly repeatedly blocking and unblocking, invoking
     * {@link #tryAcquire} until success or the thread is interrupted
     * or the timeout elapses.  This method can be used to implement
     * method {@link Lock#tryLock(long, TimeUnit)}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted()) // 如果线程被阻断，抛出异常
            throw new InterruptedException();
        return tryAcquire(arg) || // 如果首次尝试获取锁成功
            doAcquireNanos(arg, nanosTimeout); // 尝试时间
    }

    /**
     * Releases in exclusive mode.  Implemented by unblocking one or
     * more threads if {@link #tryRelease} returns true.
     * This method can be used to implement method {@link Lock#unlock}.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryRelease} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @return the value returned from {@link #tryRelease}
     */
    public final boolean release(int arg) {
        if (tryRelease(arg)) { // 如果完全释放掉
            Node h = head; // 拿到AQS正在持有锁的线程
            if (h != null && h.waitStatus != 0) // 严谨性判断，h.waitStatus等于0是初始化状态
                unparkSuccessor(h); // 唤醒下一个正在等待持有锁的线程
            return true; // 成功释放锁并唤醒下一个AQS队列中等待持有锁的线程节点
        }
        return false; // 失败
    }

    /**
     * Acquires in shared mode, ignoring interrupts.  Implemented by
     * first invoking at least once {@link #tryAcquireShared},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquireShared} until success.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     */
    /**
     * 加读锁
     */
    public final void acquireShared(int arg) {
        /**
         * 尝试获取共享锁，如果小于0，说明被加了独占锁
         * 如果被加了独占锁，那就得去获取共享锁，直到成功加上共享锁后，放行
         * 如果获取共享锁的结果大于0，就说明成功加了共享锁，就放行
         */
        if (tryAcquireShared(arg) < 0) // 小于0，没能成功加共享锁；否则往下执行，也就是自己写的代码
            doAcquireShared(arg); // 再加读锁
    }

    /**
     * Acquires in shared mode, aborting if interrupted.  Implemented
     * by first checking interrupt status, then invoking at least once
     * {@link #tryAcquireShared}, returning on success.  Otherwise the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted.
     * @param arg the acquire argument.
     * This value is conveyed to {@link #tryAcquireShared} but is
     * otherwise uninterpreted and can represent anything
     * you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (tryAcquireShared(arg) < 0)
            doAcquireSharedInterruptibly(arg);
    }

    /**
     * Attempts to acquire in shared mode, aborting if interrupted, and
     * failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquireShared}, returning on success.  Otherwise, the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted or the timeout elapses.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted()) //  // 如果需要阻断，抛出异常
            throw new InterruptedException();
        return tryAcquireShared(arg) >= 0 || // 大于0，成功加共享锁，返回尝试成功
            doAcquireSharedNanos(arg, nanosTimeout);
    }

    /**
     * Releases in shared mode.  Implemented by unblocking one or more
     * threads if {@link #tryReleaseShared} returns true.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryReleaseShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @return the value returned from {@link #tryReleaseShared}
     */
    /**
     * 释放共享锁
     */
    public final boolean releaseShared(int arg) {
        if (tryReleaseShared(arg)) { // 成功释放读锁
            doReleaseShared(); // 释放队列中的其它正在等待的共享锁
            return true; // 成功释放共享锁
        }
        return false; // 没能成功释放共享锁
    }

    // Queue inspection methods

    /**
     * Queries whether any threads are waiting to acquire. Note that
     * because cancellations due to interrupts and timeouts may occur
     * at any time, a {@code true} return does not guarantee that any
     * other thread will ever acquire.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is if an acquire method has ever blocked.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * Returns the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued.
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * @return the first (longest-waiting) thread in the queue, or
     *         {@code null} if no threads are currently queued
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * Version of getFirstQueuedThread called when fastpath fails
     */
    private Thread fullGetFirstQueuedThread() {
        /*
         * The first node is normally head.next. Try to get its
         * thread field, ensuring consistent reads: If thread
         * field is nulled out or s.prev is no longer head, then
         * some other thread(s) concurrently performed setHead in
         * between some of our reads. We try this twice before
         * resorting to traversal.
         */
        Node h, s;
        Thread st;
        if (((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null) ||
            ((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null))
            return st;

        /*
         * Head's next field might not have been set yet, or may have
         * been unset after setHead. So we must check to see if tail
         * is actually first node. If not, we continue on, safely
         * traversing from tail back to head to find first,
         * guaranteeing termination.
         */

        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
    }

    /**
     * Returns true if the given thread is currently queued.
     *
     * <p>This implementation traverses the queue to determine
     * presence of the given thread.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.thread == thread)
                return true;
        return false;
    }

    /**
     * Returns {@code true} if the apparent first queued thread, if one
     * exists, is waiting in exclusive mode.  If this method returns
     * {@code true}, and the current thread is attempting to acquire in
     * shared mode (that is, this method is invoked from {@link
     * #tryAcquireShared}) then it is guaranteed that the current thread
     * is not the first queued thread.  Used only as a heuristic in
     * ReentrantReadWriteLock.
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null && // 头节点不为NULL，有持有锁的节点线程
            (s = h.next)  != null && // 头节点的下一个节点不为NULL，有等待释放锁的节点线程
            !s.isShared()         && // 等待释放锁的节点线程不是共享锁，而是独占锁
            s.thread != null; // 等待释放锁的节点线程不为NULL，这个应该是防止初始化的时候
    }

    /**
     * Queries whether any threads have been waiting to acquire longer
     * than the current thread.
     *
     * <p>An invocation of this method is equivalent to (but may be
     * more efficient than):
     *  <pre> {@code
     * getFirstQueuedThread() != Thread.currentThread() &&
     * hasQueuedThreads()}</pre>
     *
     * <p>Note that because cancellations due to interrupts and
     * timeouts may occur at any time, a {@code true} return does not
     * guarantee that some other thread will acquire before the current
     * thread.  Likewise, it is possible for another thread to win a
     * race to enqueue after this method has returned {@code false},
     * due to the queue being empty.
     *
     * <p>This method is designed to be used by a fair synchronizer to
     * avoid <a href="AbstractQueuedSynchronizer#barging">barging</a>.
     * Such a synchronizer's {@link #tryAcquire} method should return
     * {@code false}, and its {@link #tryAcquireShared} method should
     * return a negative value, if this method returns {@code true}
     * (unless this is a reentrant acquire).  For example, the {@code
     * tryAcquire} method for a fair, reentrant, exclusive mode
     * synchronizer might look like this:
     *
     *  <pre> {@code
     * protected boolean tryAcquire(int arg) {
     *   if (isHeldExclusively()) {
     *     // A reentrant acquire; increment hold count
     *     return true;
     *   } else if (hasQueuedPredecessors()) {
     *     return false;
     *   } else {
     *     // try to acquire normally
     *   }
     * }}</pre>
     *
     * @return {@code true} if there is a queued thread preceding the
     *         current thread, and {@code false} if the current thread
     *         is at the head of the queue or the queue is empty
     * @since 1.7
     */
    /**
     * 返回AQS队列中是否有正在排队等待获取锁的线程
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = tail; // Read fields in reverse initialization order // AQS队列尾结点
        Node h = head; // AQS队列头结点
        Node s;
        return h != t && // 因为head是正在持有锁的节点，如果head节点不等于tail节点；说明至少tail节点在排队
            ((s = h.next) == null || s.thread != Thread.currentThread()); // 初始化AQS时有线程排队或head后继节点不是当前线程(有其它线程在排队)
        /**
         * (s = h.next) == null 说明，在初始化的时候neq(final Node node)方法：
         * private Node enq(final Node node) {
         *    // ......
         *    if (compareAndSetHead(new Node()))
         *    // ......
         *  }
         * 1、刚刚初始化AQS的时候，会用CAS更新head节点，并且new Node()，这个时候，他的next一定是NULL，
         *      而当前正在初始化的线程，也是在排队的线程
         * 2、s.thread也就是head后继节点的thread不等于当前线程，说明持有锁的线程节点后排队的不是当前线程
         *       (AQS队列中)还有其它线程在排队
         */
    }


    // Instrumentation and monitoring methods

    /**
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization
     * control.
     *
     * @return the estimated number of threads waiting to acquire
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null)
                ++n;
        }
        return n;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in exclusive mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to an exclusive acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in shared mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to a shared acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a string identifying this synchronizer, as well as its state.
     * The state, in brackets, includes the String {@code "State ="}
     * followed by the current value of {@link #getState}, and either
     * {@code "nonempty"} or {@code "empty"} depending on whether the
     * queue is empty.
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
            "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions

    /**
     * Returns true if a node, always one that was initially placed on
     * a condition queue, is now waiting to reacquire on sync queue.
     * @param node the node
     * @return true if is reacquiring
     */
    final boolean isOnSyncQueue(Node node) {
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;
        if (node.next != null) // If has successor, it must be on queue // 如果有后继者，它必须在队列上
            return true;
        /*
         * node.prev can be non-null, but not yet on queue because
         * the CAS to place it on queue can fail. So we have to
         * traverse from tail to make sure it actually made it.  It
         * will always be near the tail in calls to this method, and
         * unless the CAS failed (which is unlikely), it will be
         * there, so we hardly ever traverse much.
         */
        return findNodeFromTail(node); // 查询当前节点是否是尾节点
    }

    /**
     * Returns true if node is on sync queue by searching backwards from tail.
     * Called only when needed by isOnSyncQueue.
     * @return true if present
     */
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (;;) {
            if (t == node)
                return true;
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    /**
     * Transfers a node from a condition queue onto sync queue.
     * Returns true if successful.
     * @param node the node
     * @return true if successfully transferred (else the node was
     * cancelled before signal)
     */
    final boolean transferForSignal(Node node) {
        /*
         * If cannot change waitStatus, the node has been cancelled.
         */
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0)) // 如果没能将等待状态修改为初始化状态
            return false;

        /*
         * Splice onto queue and try to set waitStatus of predecessor to
         * indicate that thread is (probably) waiting. If cancelled or
         * attempt to set waitStatus fails, wake up to resync (in which
         * case the waitStatus can be transiently and harmlessly wrong).
         */
        Node p = enq(node); // 加入AQS队列
        int ws = p.waitStatus; // 等待状态
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL)) // 等待状态如果是线程作废或修改状态为Node.SIGNAL失败
            LockSupport.unpark(node.thread); // 解除挂起
        return true;
    }

    /**
     * Transfers node, if necessary, to sync queue after a cancelled wait.
     * Returns true if thread was cancelled before being signalled.
     *
     * @param node the node
     * @return true if cancelled before the node was signalled
     */
    final boolean transferAfterCancelledWait(Node node) {
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) { // 如果成功将等待状态改为初始化状态
            enq(node); // 将节点加入阻塞队列
            return true;
        }
        /*
         * If we lost out to a signal(), then we can't proceed
         * until it finishes its enq().  Cancelling during an
         * incomplete transfer is both rare and transient, so just
         * spin.
         */
        while (!isOnSyncQueue(node)) // 无限循环，直到工作线程对应的节点不再被条件队列包含为止
            Thread.yield();
        return false;
    }

    /**
     * Invokes release with current state value; returns saved state.
     * Cancels node and throws exception on failure.
     * @param node the condition node for this wait
     * @return previous sync state
     */
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            int savedState = getState(); // 节点的锁状态
            if (release(savedState)) { // 释放所有的锁，如果成功
                failed = false; // 失败状态改为false
                return savedState; // 返回当前线程的锁状态
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed)
                node.waitStatus = Node.CANCELLED; // 将当前线程的状态改为作废
        }
    }

    // Instrumentation methods for conditions

    /**
     * Queries whether the given ConditionObject
     * uses this synchronizer as its lock.
     *
     * @param condition the condition
     * @return {@code true} if owned
     * @throws NullPointerException if the condition is null
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with this synchronizer. Note that because timeouts
     * and interrupts may occur at any time, a {@code true} return
     * does not guarantee that a future {@code signal} will awaken
     * any threads.  This method is designed primarily for use in
     * monitoring of the system state.
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with this synchronizer. Note that
     * because timeouts and interrupts may occur at any time, the
     * estimate serves only as an upper bound on the actual number of
     * waiters.  This method is designed for use in monitoring of the
     * system state, not for synchronization control.
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with this
     * synchronizer.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate. The elements of the
     * returned collection are in no particular order.
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    /**
     * Condition implementation for a {@link
     * AbstractQueuedSynchronizer} serving as the basis of a {@link
     * Lock} implementation.
     *
     * <p>Method documentation for this class describes mechanics,
     * not behavioral specifications from the point of view of Lock
     * and Condition users. Exported versions of this class will in
     * general need to be accompanied by documentation describing
     * condition semantics that rely on those of the associated
     * {@code AbstractQueuedSynchronizer}.
     *
     * <p>This class is Serializable, but all fields are transient,
     * so deserialized conditions have no waiters.
     */
    /**
     * Condition底层同步器，所有的方法都是基于它来调用工作的
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        /** First node of condition queue. */
        private transient Node firstWaiter;
        /** Last node of condition queue. */
        private transient Node lastWaiter;

        /**
         * Creates a new {@code ConditionObject} instance.
         */
        public ConditionObject() { }

        // Internal methods

        /**
         * Adds a new waiter to wait queue.
         * @return its new wait node
         */
        private Node addConditionWaiter() {
            Node t = lastWaiter; // 队列的末尾节点
            // If lastWaiter is cancelled, clean out.
            if (t != null && t.waitStatus != Node.CONDITION) { // 如果lastWaiter不为NULL，并且节点状态不是等待状态
                unlinkCancelledWaiters(); // 移除非等待状态的节点
                t = lastWaiter; // 重新赋值
            }
            Node node = new Node(Thread.currentThread(), Node.CONDITION); // 创建一个新的Node节点，状态是Node.CONDITION:-2等待状态
            if (t == null) // 如果首节点是NULL
                firstWaiter = node; // 将刚刚创建的节点设置为首节点
            else
                t.nextWaiter = node; // 将刚刚创建的节点挂在队列的后面
            lastWaiter = node; // 将末尾节点设置为当前节点
            return node; // 返回当前节点
        }

        /**
         * Removes and transfers nodes until hit non-cancelled one or
         * null. Split out from signal in part to encourage compilers
         * to inline the case of no waiters.
         * @param first (non-null) the first node on condition queue
         */
        private void doSignal(Node first) {
            do { // 遍历
                if ( (firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;
                first.nextWaiter = null;
            } while (!transferForSignal(first) && // 唤醒队列中的first节点
                     (first = firstWaiter) != null);
        }

        /**
         * Removes and transfers all nodes.
         * @param first (non-null) the first node on condition queue
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
            } while (first != null);
        }

        /**
         * Unlinks cancelled waiter nodes from condition queue.
         * Called only while holding lock. This is called when
         * cancellation occurred during condition wait, and upon
         * insertion of a new waiter when lastWaiter is seen to have
         * been cancelled. This method is needed to avoid garbage
         * retention in the absence of signals. So even though it may
         * require a full traversal, it comes into play only when
         * timeouts or cancellations occur in the absence of
         * signals. It traverses all nodes rather than stopping at a
         * particular target to unlink all pointers to garbage nodes
         * without requiring many re-traversals during cancellation
         * storms.
         */
        private void unlinkCancelledWaiters() {
            Node t = firstWaiter; // 队列的首个节点
            Node trail = null;
            while (t != null) { // 迭代遍历
                Node next = t.nextWaiter;
                if (t.waitStatus != Node.CONDITION) { // 如果当前节点状态不是等待状态，就将当前节点移除
                    t.nextWaiter = null; // 当前节点的下一个节点置NULL
                    if (trail == null) // 表示当前节点是首节点
                        firstWaiter = next; // 将后继节点设置为firstWaiter
                    else
                        trail.nextWaiter = next; // 移除当前节点
                    if (next == null)
                        lastWaiter = trail;
                }
                else
                    trail = t;
                t = next;
            }
        }

        // public methods

        /**
         * Moves the longest-waiting thread, if one exists, from the
         * wait queue for this condition to the wait queue for the
         * owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        public final void signal() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null) // 如果队列首节点不为NULL
                doSignal(first); // 唤醒节点
        }

        /**
         * Moves all threads from the wait queue for this condition to
         * the wait queue for the owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        public final void signalAll() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignalAll(first);
        }

        /**
         * Implements uninterruptible condition wait.
         * <ol>
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * </ol>
         */
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean interrupted = false;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if (Thread.interrupted())
                    interrupted = true;
            }
            if (acquireQueued(node, savedState) || interrupted)
                selfInterrupt();
        }

        /*
         * For interruptible waits, we need to track whether to throw
         * InterruptedException, if interrupted while blocked on
         * condition, versus reinterrupt current thread, if
         * interrupted while blocked waiting to re-acquire.
         */

        /** Mode meaning to reinterrupt on exit from wait */
        private static final int REINTERRUPT =  1;
        /** Mode meaning to throw InterruptedException on exit from wait */
        private static final int THROW_IE    = -1;

        /**
         * Checks for interrupt, returning THROW_IE if interrupted
         * before signalled, REINTERRUPT if after signalled, or
         * 0 if not interrupted.
         */
        /**
         * 检查中断，如果在信号之前中断，返回THROW_IE;如果在信号之后中断，
         * 返回REINTERRUPT;如果没有中断，返回0。
         */
        private int checkInterruptWhileWaiting(Node node) {
            return Thread.interrupted() ? // 如果状态是被阻断
                (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                0;
        }

        /**
         * Throws InterruptedException, reinterrupts current thread, or
         * does nothing, depending on mode.
         */
        private void reportInterruptAfterWait(int interruptMode)
            throws InterruptedException {
            if (interruptMode == THROW_IE) // 模式意味着在退出等待时抛出InterruptedException
                throw new InterruptedException();
            else if (interruptMode == REINTERRUPT) // 模式意味着退出等待时重新中断
                selfInterrupt();
        }

        /**
         * Implements interruptible condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled or interrupted.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final void await() throws InterruptedException {
            if (Thread.interrupted()) // 如果线程阻断标记是true，则抛出异常结束线程运行
                throw new InterruptedException();
            Node node = addConditionWaiter(); // 新建当前节点，并加入等待队列
            int savedState = fullyRelease(node); // 将当前节点的所有锁都释放掉
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) { // 无限循环，直到工作线程对应的节点不再被条件队列包含为止
                LockSupport.park(this); // 将当前节点挂起
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            /**
             * 线程从Condition中退出了，并且被转移到AQS的等待队里，排队并尝试获取锁
             */
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null) // clean up if cancelled
                unlinkCancelledWaiters(); // 移除非等待状态的节点
            if (interruptMode != 0) // 中断线程
                reportInterruptAfterWait(interruptMode);
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return deadline - System.nanoTime();
        }

        /**
         * Implements absolute timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        //  support for instrumentation

        /**
         * Returns true if this condition was created by the given
         * synchronization object.
         *
         * @return {@code true} if owned
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * Queries whether any threads are waiting on this condition.
         * Implements {@link AbstractQueuedSynchronizer#hasWaiters(ConditionObject)}.
         *
         * @return {@code true} if there are any waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }

        /**
         * Returns an estimate of the number of threads waiting on
         * this condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitQueueLength(ConditionObject)}.
         *
         * @return the estimated number of waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }

        /**
         * Returns a collection containing those threads that may be
         * waiting on this Condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitingThreads(ConditionObject)}.
         *
         * @return the collection of threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    /**
     * Setup to support compareAndSet. We need to natively implement
     * this here: For the sake of permitting future enhancements, we
     * cannot explicitly subclass AtomicInteger, which would be
     * efficient and useful otherwise. So, as the lesser of evils, we
     * natively implement using hotspot intrinsics API. And while we
     * are at it, we do the same for other CASable fields (which could
     * otherwise be done with atomic field updaters).
     */
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                                        expect, update);
    }

    /**
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}
