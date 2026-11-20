import React from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App.jsx";
import { Theme } from "@radix-ui/themes";
import "@radix-ui/themes/styles.css";
import "./styles.css";

createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <Theme
      appearance="dark"
      accentColor="indigo"
      grayColor="slate"
      radius="medium"
      scaling="100%"
    >
      <App />
    </Theme>
  </React.StrictMode>,
);
