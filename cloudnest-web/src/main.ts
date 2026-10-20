import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import { i18n } from "./i18n";
import "element-plus/dist/index.css";
import "element-plus/theme-chalk/dark/css-vars.css";
import "./style.css";
import App from "./App.vue";
import { router } from "./router";
document.documentElement.classList.add("dark");
createApp(App)
  .use(createPinia())
  .use(router)
  .use(i18n)
  .use(ElementPlus)
  .mount("#app");
