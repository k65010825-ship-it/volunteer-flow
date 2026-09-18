import { createRouter, createWebHistory } from "vue-router";
import { getAccessToken } from "../api/http";
import { refreshSession } from "../api/auth";
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/activities" },
    {
      path: "/login",
      component: () => import("../views/LoginView.vue"),
      meta: { public: true },
    },
    {
      path: "/register",
      component: () => import("../views/RegisterView.vue"),
      meta: { public: true },
    },
    {
      path: "/join",
      component: () => import("../views/JoinOrganizationView.vue"),
    },
    {
      path: "/activities",
      component: () => import("../views/OrganizationHomeView.vue"),
    },
    {
      path: "/organizations/:organizationId/activities",
      component: () => import("../views/ActivityListView.vue"),
      props: (r) => ({ organizationId: String(r.params.organizationId) }),
    },
    {
      path: "/activities/:id",
      component: () => import("../views/ActivityDetailView.vue"),
      props: (r) => ({ id: String(r.params.id) }),
    },
    {
      path: "/registrations",
      component: () => import("../views/MyRegistrationsView.vue"),
    },
    {
      path: "/registrations/:id",
      component: () => import("../views/RegistrationStatusView.vue"),
      props: (r) => ({ id: String(r.params.id) }),
    },
  ],
});
router.beforeEach(async (to) => {
  if (to.meta.public || getAccessToken()) return true;
  try {
    // A page reload clears the in-memory access token; restore it via the refresh cookie.
    await refreshSession();
    return true;
  } catch {
    return { path: "/login", query: { redirect: to.fullPath } };
  }
});
window.addEventListener("volunteerflow:session-expired", () =>
  router.replace("/login"),
);
export default router;
