import { createRouter, createWebHistory } from "vue-router";
import { getAccessToken } from "../api/http";
import { refreshSession } from "../api/auth";
import { useAuthStore } from "../stores/auth";
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/activities" },
    { path: "/admin", redirect: "/admin/organizations" },
    {
      path: "/admin/organizations",
      component: () => import("../views/AdminOrganizationsView.vue"),
      meta: { platformAdmin: true },
    },
    { path: "/profile", component: () => import("../views/ProfileView.vue") },
    {
      path: "/forbidden",
      component: () => import("../views/ForbiddenView.vue"),
    },
    {
      path: "/organizations/:organizationId/manage",
      component: () => import("../views/OrganizationManagementView.vue"),
      props: true,
    },
    {
      path: "/organizations/:organizationId/audit",
      component: () => import("../views/AuditLogView.vue"),
      props: true,
    },
    {
      path: "/organizations/:organizationId/drafts",
      component: () => import("../views/DraftActivitiesView.vue"),
      props: true,
    },
    {
      path: "/organizations/:organizationId/activities/new",
      component: () => import("../views/ActivityEditorView.vue"),
      props: true,
    },
    {
      path: "/activities/:id/edit",
      component: () => import("../views/ActivityEditorView.vue"),
      props: true,
    },
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
      path: "/activities/:id/manage",
      component: () => import("../views/PublishedActivityManagementView.vue"),
      props: (r) => ({ id: String(r.params.id) }),
    },
    {
      path: "/activities/:id/check-in",
      component: () => import("../views/ActivityCheckinView.vue"),
      props: (r) => ({ id: String(r.params.id) }),
    },
    {
      path: "/activities/:id/check-in-management",
      component: () => import("../views/CheckinManagementView.vue"),
      props: (r) => ({ id: String(r.params.id) }),
    },
    {
      path: "/registrations",
      component: () => import("../views/MyRegistrationsView.vue"),
    },
    {
      path: "/notifications",
      component: () => import("../views/NotificationCenterView.vue"),
    },
    {
      path: "/positions/:positionId/registrations",
      component: () => import("../views/RegistrationManagementView.vue"),
      props: (r) => ({ positionId: String(r.params.positionId) }),
    },
    {
      path: "/registrations/:id",
      component: () => import("../views/RegistrationStatusView.vue"),
      props: (r) => ({ id: String(r.params.id) }),
    },
    {
      path: "/:pathMatch(.*)*",
      component: () => import("../views/NotFoundView.vue"),
    },
  ],
});
router.beforeEach(async (to) => {
  if (to.meta.public) return true;
  const auth = useAuthStore();
  try {
    // A page reload clears the in-memory access token; restore it via the refresh cookie.
    if (!getAccessToken()) await refreshSession();
    if (!auth.user) await auth.restore();
    if (!auth.user) return { path: "/login", query: { redirect: to.fullPath } };
    if (to.meta.platformAdmin && auth.user.platformRole !== "PLATFORM_ADMIN")
      return "/forbidden";
    // Platform management does not grant membership in every organization.
    if (
      to.path === "/activities" &&
      auth.user.platformRole === "PLATFORM_ADMIN" &&
      !to.query.userView
    )
      return "/admin/organizations";
    return true;
  } catch {
    return { path: "/login", query: { redirect: to.fullPath } };
  }
});
window.addEventListener("volunteerflow:session-expired", () => {
  const auth = useAuthStore();
  auth.user = null;
  auth.ready = false;
  router.replace("/login");
});
export default router;
