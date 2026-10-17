import { Nav } from "./components/ui";
import { useHashRoute } from "./components/router";
import Overview from "./views/Overview";
import Sessions from "./views/Sessions";
import SessionDetail from "./views/SessionDetail";
import "./styles.css";

export default function App() {
  const route = useHashRoute();
  return (
    <div className="app">
      <Nav routeName={route.name} />
      <main className="main">
        {route.name === "overview" && <Overview />}
        {route.name === "sessions" && <Sessions key="sessions" />}
        {route.name === "sessionDetail" && <SessionDetail key={route.id} id={route.id} />}
      </main>
      <footer className="footer">
        <span>AgentTrail — flight recorder for AI agents</span>
        <span className="mono">API: {import.meta.env.VITE_API_URL ?? "http://localhost:8080"}</span>
      </footer>
    </div>
  );
}
