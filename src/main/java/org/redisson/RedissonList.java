/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;

import org.redisson.client.protocol.RedisCommand;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.convertor.BooleanNumberReplayConvertor;
import org.redisson.client.protocol.convertor.BooleanReplayConvertor;
import org.redisson.client.protocol.convertor.Convertor;
import org.redisson.client.protocol.convertor.IntegerReplayConvertor;

import static org.redisson.client.protocol.RedisCommands.*;
import org.redisson.connection.ConnectionManager;
import org.redisson.core.RList;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

/**
 * Distributed and concurrent implementation of {@link java.util.List}
 *
 * @author Nikita Koksharov
 *
 * @param <V> the type of elements held in this collection
 */
public class RedissonList<V> extends RedissonExpirable implements RList<V> {

    protected RedissonList(ConnectionManager connectionManager, String name) {
        super(connectionManager, name);
    }

    @Override
    public int size() {
        return connectionManager.get(sizeAsync());
    }

    public Future<Integer> sizeAsync() {
        return connectionManager.readAsync(getName(), LLEN, getName());
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean contains(Object o) {
        return connectionManager.get(containsAsync(o));
    }

    @Override
    public Iterator<V> iterator() {
        return listIterator();
    }

    @Override
    public Object[] toArray() {
        List<V> list = readAll();
        return list.toArray();
    }

    private List<V> readAll() {
        return (List<V>) connectionManager.get(readAllAsync());
    }

    @Override
    public Future<Collection<V>> readAllAsync() {
        return connectionManager.readAsync(getName(), LRANGE, getName(), 0, -1);
    }

    @Override
    public <T> T[] toArray(T[] a) {
        List<V> list = readAll();
        return list.toArray(a);
    }

    @Override
    public boolean add(V e) {
        return addAll(Collections.singleton(e));
    }

    @Override
    public Future<Boolean> addAsync(V e) {
        return addAllAsync(Collections.singleton(e));
    }

    @Override
    public boolean remove(Object o) {
        return remove(o, 1);
    }

    @Override
    public Future<Boolean> removeAsync(Object o) {
        return removeAsync(o, 1);
    }

    protected Future<Boolean> removeAsync(Object o, int count) {
        return connectionManager.writeAsync(getName(), LREM_SINGLE, getName(), count, o);
    }

    protected boolean remove(Object o, int count) {
        return connectionManager.get(removeAsync(o, count));
    }

    @Override
    public Future<Boolean> containsAllAsync(Collection<?> c) {
        return connectionManager.evalReadAsync(getName(), new RedisCommand<Boolean>("EVAL", new BooleanReplayConvertor(), 4),
                "local s = redis.call('llen', KEYS[1]);" +
                        "for i = 0, s, 1 do " +
                            "for j = 0, table.getn(ARGV), 1 do "
                            + "if ARGV[j] == redis.call('lindex', KEYS[1], i) "
                            + "then table.remove(ARGV, j) end "
                        + "end; "
                        +"end;"
                        + "return table.getn(ARGV) == 0; ",
                Collections.<Object>singletonList(getName()), c.toArray());
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return connectionManager.get(containsAllAsync(c));
    }

    @Override
    public boolean addAll(Collection<? extends V> c) {
        return connectionManager.get(addAllAsync(c));
    }

    @Override
    public Future<Boolean> addAllAsync(final Collection<? extends V> c) {
        if (c.isEmpty()) {
            return connectionManager.getGroup().next().newSucceededFuture(false);
        }

        final Promise<Boolean> promise = newPromise();
        final int listSize = size();
        List<Object> args = new ArrayList<Object>(c.size() + 1);
        args.add(getName());
        args.addAll(c);
        Future<Long> res = connectionManager.writeAsync(getName(), RPUSH, args.toArray());
        res.addListener(new FutureListener<Long>() {
            @Override
            public void operationComplete(Future<Long> future) throws Exception {
                if (future.isSuccess()) {
                    promise.setSuccess(listSize != future.getNow());
                } else {
                    promise.setFailure(future.cause());
                }
            }
        });
        return promise;
    }

    @Override
    public boolean addAll(final int index, final Collection<? extends V> coll) {
        checkPosition(index);
        if (coll.isEmpty()) {
            return false;
        }
        int size = size();
        if (index < size) {

            if (index == 0) { // prepend elements to list
                List<Object> elements = new ArrayList<Object>(coll);
                Collections.reverse(elements);
                elements.add(0, getName());

                Long newSize = connectionManager.write(getName(), LPUSH, elements.toArray());
                return newSize != size;
            }

            // insert into middle of list

            List<Object> args = new ArrayList<Object>(coll.size() + 1);
            args.add(index);
            args.addAll(coll);
            return connectionManager.evalWrite(getName(), new RedisCommand<Boolean>("EVAL", new BooleanReplayConvertor(), 5),
                    "local ind = table.remove(ARGV, 1); " + // index is the first parameter
                            "local tail = redis.call('lrange', KEYS[1], ind, -1); " +
                            "redis.call('ltrim', KEYS[1], 0, ind - 1); " +
                            "for i, v in ipairs(ARGV) do redis.call('rpush', KEYS[1], v) end;" +
                            "for i, v in ipairs(tail) do redis.call('rpush', KEYS[1], v) end;" +
                            "return true",
                    Collections.<Object>singletonList(getName()), args.toArray());
        } else {
            // append to list
            return addAll(coll);
        }
    }

    @Override
    public Future<Boolean> removeAllAsync(Collection<?> c) {
        return connectionManager.evalWriteAsync(getName(), new RedisCommand<Boolean>("EVAL", new BooleanReplayConvertor(), 4),
                        "local v = false " +
                        "for i = 0, table.getn(ARGV), 1 do "
                            + "if redis.call('lrem', KEYS[1], 0, ARGV[i]) == 1 "
                            + "then v = true end "
                        +"end "
                       + "return v ",
                Collections.<Object>singletonList(getName()), c.toArray());
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return connectionManager.get(removeAllAsync(c));
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return connectionManager.get(retainAllAsync(c));
    }

    @Override
    public Future<Boolean> retainAllAsync(Collection<?> c) {
        return connectionManager.evalWriteAsync(getName(), new RedisCommand<Boolean>("EVAL", new BooleanReplayConvertor(), 4),
                    "local changed = false " +
                    "local s = redis.call('llen', KEYS[1]) "
                       + "local i = 0 "
                       + "while i < s do "
                            + "local element = redis.call('lindex', KEYS[1], i) "
                            + "local isInAgrs = false "
                            + "for j = 0, table.getn(ARGV), 1 do "
                                + "if ARGV[j] == element then "
                                    + "isInAgrs = true "
                                    + "break "
                                + "end "
                            + "end "
                            + "if isInAgrs == false then "
                                + "redis.call('LREM', KEYS[1], 0, element) "
                                + "i = i-1 "
                                + "s = s-1 "
                                + "changed = true "
                            + "end "
                            + "i = i + 1 "
                       + "end "
                       + "return changed ",
                Collections.<Object>singletonList(getName()), c.toArray());
    }


    @Override
    public void clear() {
        delete();
    }

    @Override
    public Future<V> getAsync(int index) {
        return connectionManager.readAsync(getName(), LINDEX, getName(), index);
    }

    @Override
    public V get(int index) {
        checkIndex(index);
        return getValue(index);
    }

    private V getValue(int index) {
        return connectionManager.get(getAsync(index));
    }

    private void checkIndex(int index) {
        int size = size();
        if (!isInRange(index, size))
            throw new IndexOutOfBoundsException("index: " + index + " but current size: "+ size);
    }

    private boolean isInRange(int index, int size) {
        return index >= 0 && index < size;
    }

    private void checkPosition(int index) {
        int size = size();
        if (!isPositionInRange(index, size))
            throw new IndexOutOfBoundsException("index: " + index + " but current size: "+ size);
    }

    private boolean isPositionInRange(int index, int size) {
        return index >= 0 && index <= size;
    }

    @Override
    public V set(int index, V element) {
        checkIndex(index);
        return connectionManager.get(setAsync(index, element));
    }

    @Override
    public Future<V> setAsync(int index, V element) {
        return connectionManager.evalWriteAsync(getName(), new RedisCommand<Object>("EVAL", 5),
                "local v = redis.call('lindex', KEYS[1], ARGV[1]); " +
                        "redis.call('lset', KEYS[1], ARGV[1], ARGV[2]); " +
                        "return v",
                Collections.<Object>singletonList(getName()), index, element);
    }

    @Override
    public void fastSet(int index, V element) {
        checkIndex(index);
        connectionManager.get(fastSetAsync(index, element));
    }

    @Override
    public Future<Void> fastSetAsync(int index, V element) {
        return connectionManager.writeAsync(getName(), RedisCommands.LSET, getName(), index, element);
    }

    @Override
    public void add(int index, V element) {
        addAll(index, Collections.singleton(element));
    }

    @Override
    public V remove(int index) {
        checkIndex(index);

        if (index == 0) {
            return connectionManager.write(getName(), LPOP, getName());
        }

        return connectionManager.evalWrite(getName(), EVAL_OBJECT,
                "local v = redis.call('lindex', KEYS[1], ARGV[1]); " +
                        "local tail = redis.call('lrange', KEYS[1], ARGV[1]);" +
                        "redis.call('ltrim', KEYS[1], 0, ARGV[1] - 1);" +
                        "for i, v in ipairs(tail) do redis.call('rpush', KEYS[1], v) end;" +
                        "return v",
                Collections.<Object>singletonList(getName()), index);
    }

    @Override
    public int indexOf(Object o) {
        return connectionManager.get(indexOfAsync(o, new IntegerReplayConvertor()));
    }

    @Override
    public Future<Boolean> containsAsync(Object o) {
        return indexOfAsync(o, new BooleanNumberReplayConvertor());
    }

    private <R> Future<R> indexOfAsync(Object o, Convertor<R> convertor) {
        return connectionManager.evalReadAsync(getName(), new RedisCommand<R>("EVAL", convertor, 4),
                "local s = redis.call('llen', KEYS[1]);" +
                        "for i = 0, s, 1 do if ARGV[1] == redis.call('lindex', KEYS[1], i) then return i end end;" +
                        "return -1",
                Collections.<Object>singletonList(getName()), o);
    }

    @Override
    public Future<Integer> indexOfAsync(Object o) {
        return connectionManager.evalReadAsync(getName(), new RedisCommand<Integer>("EVAL", new IntegerReplayConvertor(), 4),
                "local s = redis.call('llen', KEYS[1]);" +
                        "for i = 0, s, 1 do if ARGV[1] == redis.call('lindex', KEYS[1], i) then return i end end;" +
                        "return -1",
                Collections.<Object>singletonList(getName()), o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return connectionManager.get(lastIndexOfAsync(o));
    }

    @Override
    public Future<Integer> lastIndexOfAsync(Object o) {
        return connectionManager.evalReadAsync(getName(), new RedisCommand<Integer>("EVAL", new IntegerReplayConvertor(), 4),
                "local s = redis.call('llen', KEYS[1]);" +
                        "for i = s, 0, -1 do if ARGV[1] == redis.call('lindex', KEYS[1], i) then return i end end;" +
                        "return -1",
                Collections.<Object>singletonList(getName()), o);
    }

    @Override
    public ListIterator<V> listIterator() {
        return listIterator(0);
    }

    @Override
    public ListIterator<V> listIterator(final int ind) {
        return new ListIterator<V>() {

            private V prevCurrentValue;
            private V nextCurrentValue;
            private V currentValueHasRead;
            private int currentIndex = ind - 1;
            private boolean removeExecuted;

            @Override
            public boolean hasNext() {
                V val = RedissonList.this.getValue(currentIndex+1);
                if (val != null) {
                    nextCurrentValue = val;
                }
                return val != null;
            }

            @Override
            public V next() {
                if (nextCurrentValue == null && !hasNext()) {
                    throw new NoSuchElementException("No such element at index " + currentIndex);
                }
                currentIndex++;
                currentValueHasRead = nextCurrentValue;
                nextCurrentValue = null;
                removeExecuted = false;
                return currentValueHasRead;
            }

            @Override
            public void remove() {
                if (currentValueHasRead == null) {
                    throw new IllegalStateException("Neither next nor previous have been called");
                }
                if (removeExecuted) {
                    throw new IllegalStateException("Element been already deleted");
                }
                RedissonList.this.remove(currentValueHasRead);
                currentIndex--;
                removeExecuted = true;
                currentValueHasRead = null;
            }

            @Override
            public boolean hasPrevious() {
                if (currentIndex < 0) {
                    return false;
                }
                V val = RedissonList.this.getValue(currentIndex);
                if (val != null) {
                    prevCurrentValue = val;
                }
                return val != null;
            }

            @Override
            public V previous() {
                if (prevCurrentValue == null && !hasPrevious()) {
                    throw new NoSuchElementException("No such element at index " + currentIndex);
                }
                currentIndex--;
                removeExecuted = false;
                currentValueHasRead = prevCurrentValue;
                prevCurrentValue = null;
                return currentValueHasRead;
            }

            @Override
            public int nextIndex() {
                return currentIndex + 1;
            }

            @Override
            public int previousIndex() {
                return currentIndex;
            }

            @Override
            public void set(V e) {
                if (currentIndex >= size()-1) {
                    throw new IllegalStateException();
                }
                RedissonList.this.set(currentIndex, e);
            }

            @Override
            public void add(V e) {
                RedissonList.this.add(currentIndex+1, e);
                currentIndex++;
            }
        };
    }

    @Override
    public List<V> subList(int fromIndex, int toIndex) {
        int size = size();
        if (fromIndex < 0 || toIndex > size) {
            throw new IndexOutOfBoundsException("fromIndex: " + fromIndex + " toIndex: " + toIndex + " size: " + size);
        }
        if (fromIndex > toIndex) {
            throw new IllegalArgumentException("fromIndex: " + fromIndex + " toIndex: " + toIndex);
        }

        return connectionManager.read(getName(), LRANGE, getName(), fromIndex, toIndex - 1);
    }

    public String toString() {
        Iterator<V> it = iterator();
        if (! it.hasNext())
            return "[]";

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (;;) {
            V e = it.next();
            sb.append(e == this ? "(this Collection)" : e);
            if (! it.hasNext())
                return sb.append(']').toString();
            sb.append(',').append(' ');
        }
    }

}
