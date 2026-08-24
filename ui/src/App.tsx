import { BrowserRouter } from "react-router-dom";
import MindAgentLayout from "./components/MindAgentLayout.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";

function App() {
  return (
    <BrowserRouter>
      <ChatSessionsProvider>
        <MindAgentLayout />
      </ChatSessionsProvider>
    </BrowserRouter>
  );
}

export default App;
