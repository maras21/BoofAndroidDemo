package org.boofcv.android;

import android.graphics.Canvas;
import android.hardware.Camera;
import android.view.View;
import boofcv.android.ConvertNV21;
import boofcv.core.image.GeneralizedImageOps;
import boofcv.struct.image.ImageBase;
import boofcv.struct.image.ImageDataType;
import boofcv.struct.image.ImageSingleBand;
import boofcv.struct.image.MultiSpectral;
import georegression.struct.point.Point2D_F64;

/**
 * Processing class for displaying more complex visualizations of data.  Children of this class must properly lock
 * down the GUI when processing data that can be read/written to when updating GUI;
 *
 * @author Peter Abeles
 */
public abstract class BoofRenderProcessing<T extends ImageBase> extends Thread implements BoofProcessing {

	ImageDataType<T> imageType;
	T gray;
	T gray2;

	volatile boolean requestStop = false;
	volatile boolean running = false;

	// size of the area being down for output.  defaults to image size
	int outputWidth;
	int outputHeight;

	View view;
	Thread thread;

	protected final Object lockGui = new Object();
	protected final Object lockConvert = new Object();

	// scale and translation applied to the canvas
	double scale;
	double tranX,tranY;

	protected BoofRenderProcessing(ImageDataType<T> imageType) {
		this.imageType = imageType;
	}

	@Override
	public void init(View view, Camera camera ) {
		synchronized (lockGui) {
			this.view = view;

			Camera.Size size = camera.getParameters().getPreviewSize();
			outputWidth = size.width;
			outputHeight = size.height;
			declareImages(size.width,size.height);
		}

		// start the thread for processing
		running = true;
		start();
	}

	@Override
	public void onDraw(Canvas canvas) {
		synchronized (lockGui) {
			// the process class could have been swapped
			if( gray == null )
				return;

			int w = view.getWidth();
			int h = view.getHeight();

			// fill the window and center it
			double scaleX = w/(double)outputWidth;
			double scaleY = h/(double)outputHeight;

			scale = Math.min(scaleX,scaleY);
			tranX = (w-scale*outputWidth)/2;
			tranY = (h-scale*outputHeight)/2;

			canvas.translate((float)tranX,(float)tranY);
			canvas.scale((float)scale,(float)scale);

			render(canvas, scale);
		}
	}

	/**
	 * Converts a coordinate from pixel to the output image coordinates
	 */
	protected void imageToOutput( double x , double y , Point2D_F64 pt ) {
		pt.x = x/scale - tranX/scale;
		pt.y = y/scale - tranY/scale;
	}


	@Override
	public void convertPreview(byte[] bytes, Camera camera) {
		if( thread == null )
			return;

		synchronized ( lockConvert ) {
			if( imageType.getFamily() == ImageDataType.Family.SINGLE_BAND )
				ConvertNV21.nv21ToGray(bytes, gray.width, gray.height, (ImageSingleBand)gray,(Class)gray.getClass());
			else
				ConvertNV21.nv21ToMsRgb_U8(bytes, gray.width, gray.height, (MultiSpectral) gray );
		}
		// wake up the thread and tell it to do some processing
		thread.interrupt();
	}

	@Override
	public void stopProcessing() {
		if( thread == null )
			return;

		requestStop = true;
		while( running ) {
			// wake the thread up if needed
			thread.interrupt();
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {}
		}
	}

	@Override
	public void run() {
		thread = Thread.currentThread();
		while( !requestStop ) {
			synchronized ( thread ) {
				try {
					wait();
					if( requestStop )
						break;
				} catch (InterruptedException e) {}
			}

			// swap gray buffers so that convertPreview is modifying the copy which is not in use
			synchronized ( lockConvert ) {
				T tmp = gray;
				gray = gray2;
				gray2 = tmp;
			}

			process(gray2);

			view.postInvalidate();
		}
		running = false;
	}

	/**
	 * Image processing should be done here.  process and render will not be called at the same time, but won't
	 * be called from the same threads.
	 *
	 * Be sure to use synchroize to lock the GUI as needed inside this function!
	 */
	protected abstract void process( T gray );

	/**
	 * Visualize results here.
	 */
	protected abstract void render(  Canvas canvas , double imageToOutput );

	protected void declareImages( int width , int height ) {
		gray = imageType.createImage(width,height,3);
		gray2 = imageType.createImage(width, height, 3);
	}
}
