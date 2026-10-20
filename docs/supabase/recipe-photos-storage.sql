-- =========================================================
-- Plateful: Supabase Storage setup for recipe photos
-- =========================================================
-- Run this ONCE in the Supabase SQL editor (Dashboard > SQL Editor).
-- It is NOT a Flyway migration: Flyway owns the public schema only.
-- Safe to run twice: the bucket insert updates the bucket settings if it
-- exists, and every policy is dropped before it is recreated.
--
-- Object path format: {userId}/{uuid}.{ext}, ext = jpg | png | webp.
-- The bucket is public, so reading photos through their public URLs needs
-- no policy. The SELECT policy below is still REQUIRED for deletes: Storage
-- remove() needs both SELECT and DELETE on storage.objects, and without
-- SELECT it matches zero rows and silently deletes nothing. There is
-- deliberately no UPDATE policy: files are never overwritten, a changed
-- photo is a new file.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'recipe-photos',
    'recipe-photos',
    true,
    5242880,  -- 5 MB
    array['image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do update set
    public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "recipe-photos insert own folder" on storage.objects;
create policy "recipe-photos insert own folder"
    on storage.objects
    for insert
    to authenticated
    with check (
        bucket_id = 'recipe-photos'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "recipe-photos select own folder" on storage.objects;
create policy "recipe-photos select own folder"
    on storage.objects
    for select
    to authenticated
    using (
        bucket_id = 'recipe-photos'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "recipe-photos delete own folder" on storage.objects;
create policy "recipe-photos delete own folder"
    on storage.objects
    for delete
    to authenticated
    using (
        bucket_id = 'recipe-photos'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

-- Admin moderation: an admin at aal2 can see and delete any photo file, so removing
-- someone else's recipe or comment can also remove its files. The aal claim keeps a
-- session without a second factor from using it. SELECT is needed for the same reason as
-- above: Storage remove() matches rows through SELECT first.
drop policy if exists "recipe-photos select any (admin)" on storage.objects;
create policy "recipe-photos select any (admin)"
    on storage.objects
    for select
    to authenticated
    using (
        bucket_id = 'recipe-photos'
        and (auth.jwt() ->> 'aal') = 'aal2'
        and exists (
            select 1 from public.users u
            where u.id = auth.uid() and u.is_admin
        )
    );

drop policy if exists "recipe-photos delete any (admin)" on storage.objects;
create policy "recipe-photos delete any (admin)"
    on storage.objects
    for delete
    to authenticated
    using (
        bucket_id = 'recipe-photos'
        and (auth.jwt() ->> 'aal') = 'aal2'
        and exists (
            select 1 from public.users u
            where u.id = auth.uid() and u.is_admin
        )
    );
