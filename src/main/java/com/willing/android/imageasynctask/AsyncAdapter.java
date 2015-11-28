package com.willing.android.imageasynctask;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Willing on 2015/11/28 0028.
 */
public class AsyncAdapter extends ArrayAdapter<String>
{
    private LayoutInflater mInflater;

    // 一级内存缓存
    private LruCache<String, Bitmap> mCache;
    // 二级内存缓存
    private LruCache<String, SoftReference<Bitmap>> mSecondCache;
    // 硬盘缓存, 可能为空
    private DiskLruCache mDiskCache;

    // 是否正在滚动
    private boolean mScrolling;

    private String[] mDatas;
    private ExecutorService mExec;

    public AsyncAdapter(Context context, int resource, int textViewResourceId, String[] objects)
    {
        super(context, resource, textViewResourceId, objects);

        mDatas = objects;

        mExec = Executors.newCachedThreadPool();

        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        int memorySize = (int) (Runtime.getRuntime().maxMemory() / 8);
        mCache = new LruCache<String, Bitmap>(memorySize)
        {
            @Override
            protected int sizeOf(String key, Bitmap value)
            {
                return value.getByteCount();
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue)
            {
                mSecondCache.put(key, new SoftReference<Bitmap>(oldValue));
            }
        };
        mSecondCache = new LruCache<String, SoftReference<Bitmap>>(memorySize)
        {
            @Override
            protected int sizeOf(String key, SoftReference<Bitmap> value)
            {
                Bitmap bitmap = value.get();
                if (bitmap != null)
                {
                    return bitmap.getByteCount();
                } else
                {
                    remove(key);
                }
                return 0;
            }
        };
        try
        {
            mDiskCache = DiskLruCache.open(getDiskCacheDir(context, "image"), 1, 1, 1024 * 1024 * 40);

        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public File getDiskCacheDir(Context context, String uniqueName) {
        String cachePath = null;
        File cacheFile = null;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                || !Environment.isExternalStorageRemovable()) {
            cacheFile = context.getExternalCacheDir();
            if (cacheFile != null)
            {
                cachePath = cacheFile.getPath();
            }
        }
        if (cachePath == null)
        {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }


    @Override
    public View getView(int position, View convertView, ViewGroup parent)
    {
        ViewHolder viewHolder;
        if (convertView == null)
        {
            convertView = mInflater.inflate(R.layout.item_listview, null);
            viewHolder = new ViewHolder();
            convertView.setTag(viewHolder);

            viewHolder.imageView = (ImageView) convertView.findViewById(R.id.imageView);
        } else
        {
            viewHolder = (ViewHolder) convertView.getTag();
        }

        String url = (String) viewHolder.imageView.getTag();

        if (url != null && url.equals(mDatas[position]))
        {
            //
        }
        else
        {
            viewHolder.imageView.setImageResource(R.drawable.default_image);
        }

        url = mDatas[position];
        viewHolder.imageView.setTag(url);
        // 从软引用缓存加载数据
        SoftReference<Bitmap> softBitmap = mSecondCache.get(url);
        if (softBitmap != null)
        {
            Bitmap b = softBitmap.get();
            if (b != null)
            {
                Log.i("test", "From Soft Memory: " + url);
                viewHolder.imageView.setImageBitmap(softBitmap.get());
            }
        }
        // 从内存加载数据
        Bitmap bitmap = mCache.get(url);
        if (bitmap != null)
        {
            Log.i("test", "From memory : " + url);

            viewHolder.imageView.setImageBitmap(bitmap);
        } else if (!getScrolling())
        {
            new LoadImageTask((ListView) parent).executeOnExecutor(mExec, url);
//            new LoadImageTask((ListView)parent).execute(url);
        }

        //            }
        return convertView;
    }

    public void setScrolling(boolean b)
    {
        mScrolling = b;
        if (!b)
        {
            notifyDataSetChanged();
        }
    }

    public boolean getScrolling()
    {
        return mScrolling;
    }

    class ViewHolder
    {
        ImageView imageView;
    }


    class LoadImageTask extends AsyncTask<String, Void, Bitmap>
    {
        private final ListView mListView;
        private String url;

        public LoadImageTask(ListView view)
        {
            mListView = view;
        }

        @Override
        protected Bitmap doInBackground(String... params)
        {


            url = params[0];
            Bitmap bitmap = null;
            try
            {
                DiskLruCache.Snapshot snap = mDiskCache.get(hashKeyForDisk(url));
                if (snap != null)
                {
                    bitmap = BitmapFactory.decodeStream(snap.getInputStream(0));

                    if (bitmap != null)
                    {
                        Log.i("test", "From Disk : " + url);

                        return bitmap;
                    }
                }



                DiskLruCache.Editor editor = mDiskCache.edit(hashKeyForDisk(url));
                if (editor != null)
                {
                    if (downloadImage(params, editor.newOutputStream(0)))
                    {
                        editor.commit();

                        snap = mDiskCache.get(hashKeyForDisk(url));
                        if (snap != null)
                        {
                            bitmap = BitmapFactory.decodeStream(snap.getInputStream(0));

                            if (bitmap != null)
                            {
                                Log.i("test", "From Internet: " + url);
                            }
                        }
                    } else
                    {
                        editor.abort();
                    }
                }

            } catch (IOException e)
            {
                e.printStackTrace();
            }
            finally
            {
                if (bitmap != null)
                {
                    mCache.put(url, bitmap);
                }
            }


            return bitmap;
        }

        private boolean downloadImage(String[] params, OutputStream out)
        {

            HttpURLConnection conn = null;
            try
            {
                URL u = new URL(params[0]);
                conn = (HttpURLConnection) u.openConnection();

                conn.setReadTimeout(5000);
                conn.setConnectTimeout(5000);

                InputStream in = new BufferedInputStream(conn.getInputStream());
                int bytes = -1;
                while ((bytes = in.read()) != -1)
                {
                    out.write(bytes);
                }
                in.close();
                out.close();



            } catch (MalformedURLException e)
            {
                e.printStackTrace();
                return false;
            } catch (IOException e)
            {
                e.printStackTrace();
                return false;
            }

            return true;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap)
        {
            ImageView view = (ImageView) mListView.findViewWithTag(url);
            if (view != null && bitmap != null)
            {
                view.setImageBitmap(bitmap);
            }
        }

        public String hashKeyForDisk(String key) {
            String cacheKey;
            try {
                final MessageDigest mDigest = MessageDigest.getInstance("MD5");
                mDigest.update(key.getBytes());
                cacheKey = bytesToHexString(mDigest.digest());
            } catch (NoSuchAlgorithmException e) {
                cacheKey = String.valueOf(key.hashCode());
            }
            return cacheKey;
        }

        private String bytesToHexString(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bytes.length; i++) {
                String hex = Integer.toHexString(0xFF & bytes[i]);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        }
    }
}
