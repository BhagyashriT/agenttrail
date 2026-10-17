import { useCallback, useEffect, useState } from "react";
import { apiErrorMessage } from "../api";

export interface LoadState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  refresh: () => void;
}

/**
 * Fetch-on-mount + manual refresh hook with a cancellation guard so we never
 * set state on an unmounted component.
 */
export function useApi<T>(fn: () => Promise<T>, depsKey: string): LoadState<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  const refresh = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fn()
      .then((d) => {
        if (!cancelled) {
          setData(d);
          setLoading(false);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(apiErrorMessage(e));
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [depsKey, tick]);

  return { data, loading, error, refresh };
}
