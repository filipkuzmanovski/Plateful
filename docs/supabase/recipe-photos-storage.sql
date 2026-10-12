-- =========================================================
-- Plateful: Supabase Storage setup for recipe photos
-- =========================================================
-- Run this ONCE in the Supabase SQL editor (Dashboard > SQL Editor).
-- It is NOT a Flyway migration: Flyway owns the public schema only.
-- Safe to run twice: the bucket insert is a no-op if the bucket exists,
-- and every policy is dropped before it is recreated.
--
-- Object path format: {userId}/{uuid}.{ext}, ext = jpg | png | webp.
-- The bucket is public, so reads need no policy. There is deliberately
-- no UPDATE policy: files are never overwritten, a changed photo is a
-- new file.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'recipe-photos',
    'recipe-photos',
    true,
    5242880,  -- 5 MB
    array['image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do nothing;

drop policy if exists "recipe-photos insert own folder" on storage.objects;
create policy "recipe-photos insert own folder"
    on storage.objects
    for insert
    to authenticated
    with check (
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
