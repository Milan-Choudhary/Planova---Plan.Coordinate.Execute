import { Route, Routes } from 'react-router-dom';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { Layout } from './components/Layout';
import { DashboardPage } from './pages/DashboardPage';
import { LoginPage } from './pages/LoginPage';
import { SignupPage } from './pages/SignupPage';
import { UsersAdminPage } from './pages/UsersAdminPage';
import { AdminAuditPage } from './pages/AdminAuditPage';
import { IncidentDetailPage } from './pages/IncidentDetailPage';
import { IncidentsPage } from './pages/IncidentsPage';
import { MyTasksPage } from './pages/MyTasksPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { PlanDetailPage } from './pages/PlanDetailPage';
import { PlansPage } from './pages/PlansPage';
import { TeamsPage } from './pages/TeamsPage';

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      >
        <Route path="/" element={<DashboardPage />} />
        <Route path="/plans" element={<PlansPage />} />
        <Route path="/plans/:planId" element={<PlanDetailPage />} />
        <Route path="/incidents" element={<IncidentsPage />} />
        <Route path="/incidents/:incidentId" element={<IncidentDetailPage />} />
        <Route path="/tasks" element={<MyTasksPage />} />
        <Route path="/teams" element={<TeamsPage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route
          path="/admin/audit"
          element={
            <ProtectedRoute roles={['ADMIN']}>
              <AdminAuditPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/users"
          element={
            <ProtectedRoute roles={['ADMIN']}>
              <UsersAdminPage />
            </ProtectedRoute>
          }
        />
        <Route path="*" element={<section className="card"><h1>Page not found</h1></section>} />
      </Route>
    </Routes>
  );
}
