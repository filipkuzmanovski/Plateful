-- =========================================================
-- Plateful: Supabase Storage setup for recipe videos
-- =========================================================
-- Run this ONCE in the Supabase SQL editor (Dashboard > SQL Editor).
-- It is NOT a Flyway migration: Flyway owns the public schema only.
-- Safe to run twice: the bucket insert updates the bucket settings if it
-- exists, and every policy is dropped before it is recreated.
--
-- Object path format:
--   clip:   {userId}/{uuid}.{ext}, ext = mp4 | webm | mov
--   poster: {userId}/{uuid}.jpg   (still frame, with its own uuid)
-- The bucket is public, so reading videos through their public URLs needs
-- no policy. The SELECT policy below is still REQUIRED for deletes: Storage
-- remove() needs both SELECT and DELETE on storage.objects, and without
-- SELECT it matches zero rows and silently deletes nothing. There is
-- deliberately no UPDATE policy: files are never overwritten, a changed
-- video is a new file.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'recipe-videos',
    'recipe-videos',
    true,
    52428800,  -- 50 MB
    array['video/mp4', 'video/webm', 'video/quicktime', 'image/jpeg']
)
on conflict (id) do update set
    public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "recipe-videos insert own folder" on storage.objects;
create policy "recipe-videos insert own folder"
    on storage.objects
    for insert
    to authenticated
    with check (
        bucket_id = 'recipe-videos'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "recipe-videos select own folder" on storage.objects;
create policy "recipe-videos select own folder"
    on storage.objects
    for select
    to authenticated
    using (
        bucket_id = 'recipe-videos'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "recipe-videos delete own folder" on storage.objects;
create policy "recipe-videos delete own folder"
    on storage.objects
    for delete
    to authenticated
    using (
        bucket_id = 'recipe-videos'
        and (storage.foldername(name))[1] = auth.uid()::text
    );
