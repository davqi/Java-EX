package xdean.jex.extra.rx2.nullable.impl;

import static xdean.jex.util.function.Predicates.not;
import io.reactivex.Flowable;
import io.reactivex.Observable;

import java.util.stream.Stream;

import xdean.jex.extra.rx2.nullable.NullPolicy;
import xdean.jex.extra.rx2.nullable.NullableSource;

public class NullableArray<F> implements NullableSource<F> {
  private final F[] array;

  public NullableArray(F[] array) {
    this.array = array;
  }

  @Override
  public <T> Converter<T> policy(NullPolicy<F, T> policy) {
    Converter<T> ob = new Converter<>();
    ob.policy(policy);
    return ob;
  }

  public class Converter<T> extends OFWithPolicy<F, T> {
    @Override
    public Observable<T> observable() {
      return Observable.fromArray(get());
    }

    @Override
    public Flowable<T> flowable() {
      return Flowable.fromArray(get());
    }

    @SuppressWarnings("unchecked")
    private T[] get() {
      return (T[]) Stream.of(array)
          .map(policy)
          .filter(not(null))
          .toArray();
    }
  }
}