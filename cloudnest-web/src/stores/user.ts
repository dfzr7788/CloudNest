import { defineStore } from "pinia";
import { ref } from "vue";
import { api, type User } from "../api";
export const useUser = defineStore("user", () => {
  const user = ref<User | null>(null);
  async function load() {
    user.value = (await api.get<User>("/user/me")).data;
    return user.value;
  }
  async function logout() {
    await api.post("/auth/logout");
    user.value = null;
  }
  return { user, load, logout };
});
