import { useAuth } from '@/auth/AuthProvider';

export default function MyProfilePage() {
  const { profile } = useAuth();
  return <h1 className="text-2xl font-extrabold">{profile ? `${profile.firstName} ${profile.lastName}` : ''}</h1>;
}
