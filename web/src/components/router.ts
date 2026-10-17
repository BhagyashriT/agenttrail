import { useEffect, useState } from "react";

export type Route =
  | { name: "overview" }
  | { name: "sessions" }
  | { name: "sessionDetail"; id: string };

function parseHash(): Route {
  const hash = window.location.hash.replace(/^#/, "") || "/";
  if (hash === "/") return { name: "overview" };
  if (hash === "/sessions") return { name: "sessions" };
  const m = /^\/sessions\/([^/?#]+)/.exec(hash);
  if (m) return { name: "sessionDetail", id: decodeURIComponent(m[1]) };
  return { name: "overview" };
}

export function useHashRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parseHash());
  useEffect(() => {
    const onChange = () => setRoute(parseHash());
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}

export function navigate(to: string): void {
  window.location.hash = to;
}
