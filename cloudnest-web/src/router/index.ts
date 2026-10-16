import { createRouter, createWebHistory } from "vue-router";
import { api } from "../api";
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/login", component: () => import("../views/LoginView.vue") },
    {
      path: "/s/:token",
      component: () => import("../views/ShareView.vue"),
      props: true,
    },
    { path: "/", component: () => import("../views/DriveView.vue") },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach(async (to) => {
  if (to.path === "/") {
    try {
      await api.get("/user/me");
    } catch {
      return "/login";
    }
  }
});
